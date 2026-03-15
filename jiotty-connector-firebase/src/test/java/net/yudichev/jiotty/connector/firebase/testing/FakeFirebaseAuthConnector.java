package net.yudichev.jiotty.connector.firebase.testing;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.connector.firebase.FirebaseAuthConnector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    public List<String> requestedTokens() {
        synchronized (lock) {
            return ImmutableList.copyOf(requestedTokens);
        }
    }

    @Override
    public CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> verifyUserToken(String idToken) {
        synchronized (lock) {
            String token = checkToken(idToken);
            requestedTokens.add(token);
            ResponseFactory responseFactory = responseFactoriesByToken.get(token);
            checkState(responseFactory != null, "No fake FirebaseAuthConnector response configured for token %s", token);
            return responseFactory.create();
        }
    }

    private static String checkToken(String token) {
        checkNotNull(token, "token");
        checkArgument(!token.isBlank(), "token must not be blank");
        return token;
    }

    private interface ResponseFactory {
        CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> create();
    }
}
