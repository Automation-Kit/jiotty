package net.yudichev.jiotty.connector.icloud.status;

import okhttp3.Interceptor;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkArgument;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// An [Interceptor] that fails a call when the body of its response is longer than a limit. The check reads at most one byte more than the limit.
final class BodySizeLimitInterceptor implements Interceptor {
    private static final Logger logger = LogManager.getLogger(BodySizeLimitInterceptor.class);

    private final long limitBytes;

    public BodySizeLimitInterceptor(long limitBytes) {
        checkArgument(limitBytes > 0, "limitBytes must be positive, but was %s", limitBytes);
        this.limitBytes = limitBytes;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        BufferedSource source = response.body().source();
        try {
            // The caller reads the body from its first byte.
            source.request(limitBytes + 1);
            if (source.getBuffer().size() > limitBytes) {
                throw new IOException("the response body is longer than " + limitBytes + " bytes");
            }
        } catch (IOException | RuntimeException e) {
            closeSafelyIfNotNull(logger, response);
            throw e;
        }
        return response;
    }
}
