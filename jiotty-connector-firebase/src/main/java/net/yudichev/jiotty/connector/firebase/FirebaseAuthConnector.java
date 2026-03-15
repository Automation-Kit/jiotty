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
    CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> verifyUserToken(String idToken);

    sealed interface VerificationFailure permits InvalidToken, DisabledUser {}

    /// Verified Firebase user and the linked provider identities returned by Firebase.
    ///
    /// @param linkedIdentities linked identities with distinct [UserIdentity#provider()] values; this connector guarantees it never returns duplicate providers
    record VerifiedUserToken(String firebaseUid,
                             FirebaseUserProfile firebaseProfile,
                             List<UserIdentity> linkedIdentities,
                             Instant issuedAt,
                             Instant expiresAt) {
        public VerifiedUserToken {
            checkNotNull(firebaseUid, "firebaseUid");
            checkArgument(!firebaseUid.isBlank(), "firebaseUid must not be blank");
            checkNotNull(firebaseProfile, "firebaseProfile");
            linkedIdentities = ImmutableList.copyOf(checkNotNull(linkedIdentities, "linkedIdentities"));
            checkNotNull(issuedAt, "issuedAt");
            checkNotNull(expiresAt, "expiresAt");
        }
    }

    /// Firebase-backed user profile fields exposed by Firebase Auth.
    record FirebaseUserProfile(Optional<String> email,
                               Optional<String> displayName) {
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
