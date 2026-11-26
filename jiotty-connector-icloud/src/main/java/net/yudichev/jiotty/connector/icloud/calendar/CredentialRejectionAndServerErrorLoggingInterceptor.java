package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.rest.HttpStatuses;
import net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.Exchange;
import okhttp3.Interceptor;
import okhttp3.Response;
import okio.BufferedSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.appendHumanReadableMessage;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isServerError;

/// An [Interceptor] that logs, for every response with status {@value HttpStatuses#UNAUTHORIZED_401} or a server error, what distinguishes one such
/// response from another:
///
/// - the [Exchange]
/// - the status
/// - the server's response time
/// - the body's length
/// - the names of the response headers
/// - the values of the {@value HttpHeaders#SERVER}, {@value HttpHeaders#RETRY_AFTER}, {@value HttpHeaders#CONTENT_TYPE} and
///   {@value HttpHeaders#WWW_AUTHENTICATE} headers
/// - the part of {@value #RESPONDING_INSTANCE_HEADER} before its first colon
///
/// iCloud identifies the account and the request in other header values and may quote the account in the body, so the log line carries neither.
final class CredentialRejectionAndServerErrorLoggingInterceptor implements Interceptor {
    private static final Logger logger = LogManager.getLogger(CredentialRejectionAndServerErrorLoggingInterceptor.class);

    @VisibleForTesting
    static final String RESPONDING_INSTANCE_HEADER = "x-responding-instance";
    private static final List<String> LOGGED_HEADERS =
            ImmutableList.of(HttpHeaders.SERVER, HttpHeaders.RETRY_AFTER, HttpHeaders.CONTENT_TYPE, HttpHeaders.WWW_AUTHENTICATE);

    private final String logSubjectId;

    public CredentialRejectionAndServerErrorLoggingInterceptor(String logSubjectId) {
        this.logSubjectId = checkNotNull(logSubjectId);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        int status = response.code();
        if (status == UNAUTHORIZED_401 || isServerError(status)) {
            logger.info("[{}] The server answered the {} request with {} in {} ms: body of {}, header names {}, header values [{}]",
                        logSubjectId, chain.request().tag(Exchange.class), status, response.receivedResponseAtMillis() - response.sentRequestAtMillis(),
                        describeBodyLength(response), response.headers().names(), describeLoggedHeaders(response));
        }
        return response;
    }

    /// Measures the body with [BufferedSource#request(long)], which buffers the bytes without consuming them. A reader of the body therefore reads it from
    /// its first byte.
    ///
    /// @return a description of the body's length, which is:
    ///
    /// - for a body of at most 64 KiB, its length;
    /// - for a longer body, the number of its bytes buffered, which is at least 64 KiB;
    /// - when the measurement fails, that failure
    private static StringFormattable describeBodyLength(Response response) {
        BufferedSource source = response.body().source();
        try {
            // An error body is a few hundred bytes.
            source.request(64 * 1024);
        } catch (IOException e) {
            return appendable -> {
                Append.to(appendable, "unknown length (");
                appendHumanReadableMessage(e, appendable);
                Append.to(appendable, ')');
            };
        }
        long length = source.getBuffer().size();
        return appendable -> {
            Append.to(appendable, length);
            Append.to(appendable, " bytes");
        };
    }

    private static StringFormattable describeLoggedHeaders(Response response) {
        return appendable -> appendLoggedHeaders(appendable, response);
    }

    private static void appendLoggedHeaders(Appendable appendable, Response response) {
        boolean anyAppended = false;
        for (String name : LOGGED_HEADERS) {
            String value = response.header(name);
            if (value != null) {
                appendName(appendable, name, anyAppended);
                Append.to(appendable, value);
                anyAppended = true;
            }
        }
        String respondingInstance = response.header(RESPONDING_INSTANCE_HEADER);
        if (respondingInstance != null) {
            int colonIndex = respondingInstance.indexOf(':');
            if (colonIndex > 0) {
                appendName(appendable, RESPONDING_INSTANCE_HEADER, anyAppended);
                Append.to(appendable, respondingInstance, 0, colonIndex);
            }
        }
    }

    /// @param afterAnotherHeader whether another header precedes the header named `name`, in which case a comma separates the two
    private static void appendName(Appendable appendable, String name, boolean afterAnotherHeader) {
        if (afterAnotherHeader) {
            Append.to(appendable, ", ");
        }
        Append.to(appendable, name);
        Append.to(appendable, '=');
    }
}
