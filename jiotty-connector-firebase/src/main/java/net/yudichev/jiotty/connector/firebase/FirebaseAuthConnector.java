package net.yudichev.jiotty.connector.firebase;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.user.persistence.UserIdentity;

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

    CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> verifyUserToken(String idToken);

    /// Permanently deletes the Firebase user record. Completes normally if the user is already absent.
    ///
    /// The returned [CompletableFuture] fails only for unexpected technical problems such as network failures or backend unavailability.
    CompletableFuture<Void> deleteUser(String firebaseUid);

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
