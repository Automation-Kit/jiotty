package net.yudichev.jiotty.connector.firebase;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.user.persistence.UserIdentity;
import net.yudichev.jiotty.user.persistence.UserIdentityRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Verifies a Firebase ID token and loads the corresponding Firebase user record.
///
/// Invalid or disabled-user outcomes are returned in the right side of [Either]. The returned [CompletableFuture] fails only for unexpected technical problems
/// such as network failures or backend unavailability.
public interface FirebaseAuthConnector {
    /// The [UserIdentity#provider()] value for identities sourced from Firebase.
    String IDENTITY_PROVIDER = "firebase";
    /// The Firebase provider id, and so the [UserIdentity#provider()] value, of a linked Google sign-in.
    String GOOGLE_PROVIDER_ID = "google.com";
    /// The Firebase provider id, and so the [UserIdentity#provider()] value, of a linked Apple sign-in.
    String APPLE_PROVIDER_ID = "apple.com";

    /// @return the Firebase uid among a user's `identities`, or empty when none of them is a Firebase identity
    static Optional<String> firebaseUidOf(List<UserIdentityRecord> identities) {
        for (UserIdentityRecord identityRecord : identities) {
            UserIdentity identity = identityRecord.identity();
            if (IDENTITY_PROVIDER.equals(identity.provider())) {
                return Optional.of(identity.providerUserId());
            }
        }
        return Optional.empty();
    }

    CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> verifyUserToken(String idToken);

    /// Permanently deletes the Firebase user record. Completes normally if the user is already absent, and never completes on a connector that is not
    /// started.
    ///
    /// The returned [CompletableFuture] fails only for unexpected technical problems such as network failures or backend unavailability.
    CompletableFuture<Void> deleteUser(String firebaseUid);

    /// Revokes every refresh token of the Firebase user, which ends each of the user's sessions at its next ID token renewal and makes every ID token
    /// issued in an earlier second than the revocation fail verification; one issued in the revocation's own second still verifies. Completes normally if
    /// the user is absent, who has no sessions to revoke, and never completes on a connector that is not started.
    CompletableFuture<Void> revokeSessions(String firebaseUid);

    sealed interface VerificationFailure permits InvalidToken, DisabledUser {}

    /// Verified Firebase user and the linked provider identities returned by Firebase.
    ///
    /// @param linkedIdentities linked identities with distinct [UserIdentity#provider()] values; this connector guarantees it never returns duplicate providers
    /// @param authTime         when the user last authenticated (the `auth_time` claim), used to enforce recent re-authentication for sensitive operations
    record VerifiedUserToken(String firebaseUid,
                             FirebaseUserProfile firebaseProfile,
                             List<UserIdentity> linkedIdentities,
                             Instant authTime,
                             Instant issuedAt,
                             Instant expiresAt) {
        public VerifiedUserToken {
            checkNotNull(firebaseUid, "firebaseUid");
            checkArgument(!firebaseUid.isBlank(), "firebaseUid must not be blank");
            checkNotNull(firebaseProfile, "firebaseProfile");
            linkedIdentities = ImmutableList.copyOf(checkNotNull(linkedIdentities, "linkedIdentities"));
            checkNotNull(authTime, "authTime");
            checkNotNull(issuedAt, "issuedAt");
            checkNotNull(expiresAt, "expiresAt");
        }
    }

    /// Firebase-backed user profile fields exposed by Firebase Auth.
    ///
    /// @param email         the account's email address, absent when the account has none
    /// @param displayName   the account's display name, absent when the account has none
    /// @param emailVerified whether the account's email address has been verified, current as of this token verification
    record FirebaseUserProfile(Optional<String> email,
                               Optional<String> displayName,
                               boolean emailVerified) {
        public FirebaseUserProfile {
            checkNotNull(email, "email");
            email.ifPresent(value -> checkArgument(!value.isBlank(), "email must not be blank"));
            checkNotNull(displayName, "displayName");
            displayName.ifPresent(value -> checkArgument(!value.isBlank(), "displayName must not be blank"));
        }
    }

    record InvalidToken(String technicalDescription) implements VerificationFailure {
        /// The [#technicalDescription()] of a token refused because its user's sessions were revoked.
        public static final String REVOKED_DESCRIPTION = "Firebase token has been revoked";

        public InvalidToken {
            checkNotNull(technicalDescription, "technicalDescription");
            checkArgument(!technicalDescription.isBlank(), "technicalDescription must not be blank");
        }
    }

    record DisabledUser(String firebaseUid, String technicalDescription) implements VerificationFailure {
        public DisabledUser {
            checkNotNull(firebaseUid, "firebaseUid");
            checkArgument(!firebaseUid.isBlank(), "firebaseUid must not be blank");
            checkNotNull(technicalDescription, "technicalDescription");
            checkArgument(!technicalDescription.isBlank(), "technicalDescription must not be blank");
        }
    }
}
