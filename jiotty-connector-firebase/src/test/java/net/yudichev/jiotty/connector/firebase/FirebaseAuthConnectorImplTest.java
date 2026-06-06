package net.yudichev.jiotty.connector.firebase;

import com.google.api.core.ApiFutures;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.user.persistence.UserIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static net.yudichev.jiotty.connector.firebase.FirebaseAuthConnector.DisabledUser;
import static net.yudichev.jiotty.connector.firebase.FirebaseAuthConnector.InvalidToken;
import static net.yudichev.jiotty.connector.firebase.FirebaseAuthConnector.VerifiedUserToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirebaseAuthConnectorImplTest {
    private static final String TOKEN = "token-1";
    private static final String FIREBASE_UID = "firebase-user-1";
    private static final Instant AUTH_TIME = Instant.parse("2026-03-14T08:55:00Z");
    private static final Instant ISSUED_AT = Instant.parse("2026-03-14T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-03-14T10:00:00Z");

    @Mock
    private FirebaseApp firebaseApp;
    @Mock
    private FirebaseAuth firebaseAuth;
    @Mock
    private FirebaseToken decodedToken;
    @Mock
    private UserRecord userRecord;

    private FirebaseAuthConnectorImpl connector;

    @BeforeEach
    void setUp() {
        connector = new FirebaseAuthConnectorImpl(Optional.empty(), Optional.empty(), Duration.ofSeconds(5), firebaseApp, firebaseAuth);
        connector.start();
    }

    @AfterEach
    void tearDown() {
        if (connector != null) {
            connector.stop();
        }
    }

    @Test
    void verifyUserTokenReturnsAuthenticatedUser(@Mock UserInfo googleIdentity, @Mock UserInfo passwordIdentity) {
        stubDecodedToken();
        stubActiveUser(googleIdentity, passwordIdentity);

        Optional<VerifiedUserToken> verifiedUserToken =
                assertThat(connector.verifyUserToken(TOKEN)).succeedsWithin(Duration.ZERO).extracting(Either::getLeft).actual();
        assertThat(verifiedUserToken).hasValueSatisfying(token -> {
            assertThat(token.firebaseUid()).isEqualTo(FIREBASE_UID);
            assertThat(token.firebaseProfile().email()).contains("user@example.com");
            assertThat(token.firebaseProfile().displayName()).contains("Alex");
            assertThat(token.linkedIdentities()).containsExactly(new UserIdentity("google.com", "google-user-1"));
            assertThat(token.authTime()).isEqualTo(AUTH_TIME);
            assertThat(token.issuedAt()).isEqualTo(ISSUED_AT);
            assertThat(token.expiresAt()).isEqualTo(EXPIRES_AT);
        });
    }

    @Test
    void verifyUserTokenReturnsDisabledUserWhenFirebaseUserIsDisabled() {
        when(firebaseAuth.verifyIdTokenAsync(TOKEN, false)).thenReturn(ApiFutures.immediateFuture(decodedToken));
        when(decodedToken.getUid()).thenReturn(FIREBASE_UID);
        when(firebaseAuth.getUserAsync(FIREBASE_UID)).thenReturn(ApiFutures.immediateFuture(userRecord));
        when(userRecord.isDisabled()).thenReturn(true);

        assertThat(connector.verifyUserToken(TOKEN)).succeedsWithin(Duration.ZERO)
                                                    .isEqualTo(Either.right(new DisabledUser(FIREBASE_UID,
                                                                                             "Firebase user is disabled")));
    }

    @ParameterizedTest
    @EnumSource(value = AuthErrorCode.class, names = {"INVALID_ID_TOKEN", "EXPIRED_ID_TOKEN", "REVOKED_ID_TOKEN", "USER_NOT_FOUND"})
    void verifyUserTokenMapsKnownFirebaseVerificationFailuresToInvalidToken(AuthErrorCode authErrorCode) {
        FirebaseAuthException firebaseFailure = firebaseAuthException(authErrorCode, "bad token");
        when(firebaseAuth.verifyIdTokenAsync(TOKEN, false)).thenReturn(ApiFutures.immediateFailedFuture(firebaseFailure));

        assertThat(connector.verifyUserToken(TOKEN)).succeedsWithin(Duration.ZERO)
                                                    .isEqualTo(Either.right(new InvalidToken(
                                                            "Firebase token is invalid: authErrorCode=" + authErrorCode + ": bad token")));
    }

    @Test
    void verifyUserTokenFailsForTransientFirebaseVerificationFailure() {
        FirebaseAuthException firebaseFailure = firebaseAuthException(AuthErrorCode.CERTIFICATE_FETCH_FAILED, "firebase unavailable");
        when(firebaseAuth.verifyIdTokenAsync(TOKEN, false)).thenReturn(ApiFutures.immediateFailedFuture(firebaseFailure));

        assertThat(connector.verifyUserToken(TOKEN)).failsWithin(Duration.ZERO)
                                                    .withThrowableThat()
                                                    .isInstanceOf(ExecutionException.class)
                                                    .havingCause()
                                                    .isInstanceOf(RuntimeException.class)
                                                    .withMessage(
                                                            "Firebase token verification failed: authErrorCode=CERTIFICATE_FETCH_FAILED: firebase unavailable")
                                                    .havingCause()
                                                    .isSameAs(firebaseFailure);
    }

    @Test
    void verifyUserTokenFailsWhenFirebaseReturnsDuplicateProviderIdentities(@Mock UserInfo firstGoogleIdentity, @Mock UserInfo secondGoogleIdentity) {
        stubDecodedToken();
        when(firebaseAuth.getUserAsync(FIREBASE_UID)).thenReturn(ApiFutures.immediateFuture(userRecord));
        when(userRecord.isDisabled()).thenReturn(false);
        when(userRecord.getTokensValidAfterTimestamp()).thenReturn(0L);
        when(userRecord.getEmail()).thenReturn("user@example.com");
        when(userRecord.getDisplayName()).thenReturn("Alex");
        when(userRecord.getProviderData()).thenReturn(new UserInfo[]{firstGoogleIdentity, secondGoogleIdentity});
        when(firstGoogleIdentity.getProviderId()).thenReturn("google.com");
        when(firstGoogleIdentity.getUid()).thenReturn("google-user-1");
        when(secondGoogleIdentity.getProviderId()).thenReturn("google.com");
        when(secondGoogleIdentity.getUid()).thenReturn("google-user-2");

        assertThat(connector.verifyUserToken(TOKEN)).failsWithin(Duration.ZERO)
                                                    .withThrowableThat()
                                                    .havingRootCause()
                                                    .isInstanceOf(IllegalStateException.class)
                                                    .withMessage("Unexpected firebase user record data: duplicate provider identities for provider google.com");
    }

    @Test
    void deleteUserDelegatesToFirebase() {
        when(firebaseAuth.deleteUserAsync(FIREBASE_UID)).thenReturn(ApiFutures.immediateFuture(null));

        assertThat(connector.deleteUser(FIREBASE_UID)).succeedsWithin(Duration.ZERO);
        verify(firebaseAuth).deleteUserAsync(FIREBASE_UID);
    }

    @Test
    void deleteUserCompletesNormallyWhenUserAlreadyAbsent() {
        when(firebaseAuth.deleteUserAsync(FIREBASE_UID)).thenReturn(
                ApiFutures.immediateFailedFuture(firebaseAuthException(AuthErrorCode.USER_NOT_FOUND, "no such user")));

        assertThat(connector.deleteUser(FIREBASE_UID)).succeedsWithin(Duration.ZERO);
    }

    @Test
    void deleteUserFailsForTransientFirebaseFailure() {
        when(firebaseAuth.deleteUserAsync(FIREBASE_UID)).thenReturn(
                ApiFutures.immediateFailedFuture(firebaseAuthException(AuthErrorCode.CERTIFICATE_FETCH_FAILED, "firebase unavailable")));

        assertThat(connector.deleteUser(FIREBASE_UID)).failsWithin(Duration.ZERO)
                                                      .withThrowableThat()
                                                      .havingCause()
                                                      .isInstanceOf(RuntimeException.class)
                                                      .withMessage("Failed deleting Firebase user " + FIREBASE_UID);
    }

    @Test
    void stopDeletesInjectedFirebaseApp() {
        connector.stop();

        verify(firebaseApp).delete();
    }

    private void stubDecodedToken() {
        when(firebaseAuth.verifyIdTokenAsync(TOKEN, false)).thenReturn(ApiFutures.immediateFuture(decodedToken));
        when(decodedToken.getUid()).thenReturn(FIREBASE_UID);
        when(decodedToken.getClaims()).thenReturn(Map.of("auth_time", AUTH_TIME.getEpochSecond(),
                                                         "iat", ISSUED_AT.getEpochSecond(),
                                                         "exp", EXPIRES_AT.getEpochSecond()));
    }

    private void stubActiveUser(UserInfo googleIdentity, UserInfo passwordIdentity) {
        when(firebaseAuth.getUserAsync(FIREBASE_UID)).thenReturn(ApiFutures.immediateFuture(userRecord));
        when(userRecord.isDisabled()).thenReturn(false);
        when(userRecord.getTokensValidAfterTimestamp()).thenReturn(ISSUED_AT.minusSeconds(1).toEpochMilli());
        when(userRecord.getEmail()).thenReturn("user@example.com");
        when(userRecord.getDisplayName()).thenReturn("Alex");
        when(userRecord.getProviderData()).thenReturn(new UserInfo[]{googleIdentity, passwordIdentity});
        when(googleIdentity.getProviderId()).thenReturn("google.com");
        when(googleIdentity.getUid()).thenReturn("google-user-1");
        when(passwordIdentity.getProviderId()).thenReturn("password");
        when(passwordIdentity.getUid()).thenReturn("password-user-ignored");
    }

    private static FirebaseAuthException firebaseAuthException(AuthErrorCode authErrorCode, String message) {
        return new FirebaseAuthException(ErrorCode.INVALID_ARGUMENT, message, null, null, authErrorCode);
    }
}
