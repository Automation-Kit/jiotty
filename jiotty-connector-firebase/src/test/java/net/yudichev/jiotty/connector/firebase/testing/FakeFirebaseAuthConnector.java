package net.yudichev.jiotty.connector.firebase.testing;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.connector.firebase.FirebaseAuthConnector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;

public final class FakeFirebaseAuthConnector implements FirebaseAuthConnector {
    private final Object lock = new Object();
    private final Map<String, ResponseFactory> responseFactoriesByToken = new HashMap<>();
    private final List<String> requestedTokens = new ArrayList<>();
    private final List<String> deletedUsers = new ArrayList<>();
    /// The users [#revokeSessions] was called for, whose every verification completes with [InvalidToken], the result Firebase gives for a token issued
    /// before the revocation.
    private final Set<String> revokedUsers = new HashSet<>();

    public void setVerifiedUserToken(String token, VerifiedUserToken verifiedUserToken) {
        synchronized (lock) {
            responseFactoriesByToken.put(checkToken(token), () -> completedFuture(Either.left(checkNotNull(verifiedUserToken, "verifiedUserToken"))));
        }
    }

    public void setVerificationFailure(String token, VerificationFailure verificationFailure) {
        synchronized (lock) {
            responseFactoriesByToken.put(checkToken(token), () -> completedFuture(Either.right(checkNotNull(verificationFailure, "verificationFailure"))));
        }
    }

    public void setTechnicalFailure(String token, Throwable throwable) {
        synchronized (lock) {
            responseFactoriesByToken.put(checkToken(token), () -> failedFuture(checkNotNull(throwable, "throwable")));
        }
    }

    /// Configures `token` to return a future that stays pending until the caller completes the returned handle, so a test can exercise the
    /// validation-in-flight window. Every verification of `token` returns this same future.
    public CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> deferResponse(String token) {
        var future = new CompletableFuture<Either<VerifiedUserToken, VerificationFailure>>();
        synchronized (lock) {
            responseFactoriesByToken.put(checkToken(token), () -> future);
        }
        return future;
    }

    public List<String> requestedTokens() {
        synchronized (lock) {
            return ImmutableList.copyOf(requestedTokens);
        }
    }

    public List<String> deletedUsers() {
        synchronized (lock) {
            return ImmutableList.copyOf(deletedUsers);
        }
    }

    @Override
    public CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> verifyUserToken(String idToken) {
        synchronized (lock) {
            String token = checkToken(idToken);
            requestedTokens.add(token);
            ResponseFactory responseFactory = responseFactoriesByToken.get(token);
            checkState(responseFactory != null, "No fake FirebaseAuthConnector response configured for token %s", token);
            return responseFactory.create().thenApply(this::refuseIfRevoked);
        }
    }

    @Override
    public CompletableFuture<Void> deleteUser(String firebaseUid) {
        synchronized (lock) {
            deletedUsers.add(checkFirebaseUid(firebaseUid));
            return completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Void> revokeSessions(String firebaseUid) {
        synchronized (lock) {
            revokedUsers.add(checkFirebaseUid(firebaseUid));
            return completedFuture(null);
        }
    }

    private Either<VerifiedUserToken, VerificationFailure> refuseIfRevoked(Either<VerifiedUserToken, VerificationFailure> verification) {
        boolean revoked;
        synchronized (lock) {
            revoked = verification.map(verifiedUserToken -> revokedUsers.contains(verifiedUserToken.firebaseUid()), _ -> false);
        }
        return revoked ? Either.right(new InvalidToken(InvalidToken.REVOKED_DESCRIPTION)) : verification;
    }

    private static String checkFirebaseUid(String firebaseUid) {
        return checkNotBlank(firebaseUid, "firebaseUid");
    }

    private static String checkToken(String token) {
        return checkNotBlank(token, "token");
    }

    private static String checkNotBlank(String value, String name) {
        checkNotNull(value, name);
        checkArgument(!value.isBlank(), "%s must not be blank", name);
        return value;
    }

    private interface ResponseFactory {
        CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> create();
    }
}
