package net.yudichev.jiotty.common.misc;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.HttpStatuses;

import static com.google.common.base.Throwables.getCausalChain;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;

/// Whether a failed call says the credential it carried is no longer accepted, so retrying cannot succeed and the user must authorise again. The causal chain
/// is searched for an [HttpResponseException] — always a leaf, so at most one is ever present — and only
/// {@value HttpStatuses#UNAUTHORIZED_401} counts, because a {@value HttpStatuses#FORBIDDEN_403} can mean a scope the credential never had or a resource this
/// account may not touch, neither of which re-authenticating fixes.
public final class RejectedCredential {

    private RejectedCredential() {
    }

    public static boolean indicatesRejectedCredential(Throwable throwable) {
        for (Throwable cause : getCausalChain(throwable)) {
            if (cause instanceof HttpResponseException httpResponseException) {
                return httpResponseException.statusCode() == UNAUTHORIZED_401;
            }
        }
        return false;
    }
}
