package net.yudichev.jiotty.common.misc;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.HttpStatuses;

import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;

/// Whether a failed call says the credential it carried is no longer accepted, so retrying cannot succeed and the user must authorise again. Only an
/// [HttpResponseException] carrying {@value HttpStatuses#UNAUTHORIZED_401} counts, because a {@value HttpStatuses#FORBIDDEN_403} can mean a scope the
/// credential never had or a resource this account may not touch, neither of which re-authenticating fixes.
public final class RejectedCredential {

    private RejectedCredential() {
    }

    public static boolean indicatesRejectedCredential(Throwable throwable) {
        return HttpResponseException.findIn(throwable).filter(httpResponse -> httpResponse.statusCode() == UNAUTHORIZED_401).isPresent();
    }
}
