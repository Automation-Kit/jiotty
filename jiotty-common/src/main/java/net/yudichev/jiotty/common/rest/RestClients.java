package net.yudichev.jiotty.common.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.MoreExecutors;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.StringFormattable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;

public final class RestClients {
    /// What [HttpResponseException#body()] carries in place of the response's body when a call made through
    /// [#callSuppressingResponseBody(Call, Class, int)] or one of its overloads gets an unsuccessful response, unless the call's
    /// [UnsuccessfulResponseDetailExtractor] extracts a part of the body to carry instead.
    public static final String WITHHELD_BODY = "<withheld: may carry personal data>";

    private static final Logger logger = LogManager.getLogger(RestClients.class);

    private static final int DEFAULT_CALL_RETRY_COUNT = 3;
    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(60);
    private static final AtomicInteger requestIdGenerator = new AtomicInteger();

    private RestClients() {
    }

    public static OkHttpClient newClient() {
        return newClient(_ -> {});
    }

    /// Creates a client that gives up on a connect, a read, a write or a whole call once `timeout` has passed.
    public static OkHttpClient newClient(Duration timeout) {
        return createBuilder(timeout).build();
    }

    public static OkHttpClient newClient(Consumer<? super OkHttpClient.Builder> customizer) {
        OkHttpClient.Builder builder = createBuilder(DEFAULT_HTTP_TIMEOUT);
        customizer.accept(builder);
        return builder.build();
    }

    private static OkHttpClient.Builder createBuilder(Duration timeout) {
        return new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(timeout)
                .callTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout);
    }

    public static <T> CompletableFuture<T> call(Call theCall, Class<? extends T> responseType) {
        return call(theCall, responseType, DEFAULT_CALL_RETRY_COUNT);
    }

    public static <T> CompletableFuture<T> call(Call theCall, TypeToken<? extends T> responseType) {
        return call(theCall, responseType, DEFAULT_CALL_RETRY_COUNT);
    }

    public static <T> CompletableFuture<T> call(Call theCall, Class<? extends T> responseType, int retryCount) {
        return call(theCall, TypeToken.of(responseType), retryCount);
    }

    public static <T> CompletableFuture<T> call(Call theCall, TypeToken<? extends T> responseType, int retryCount) {
        return call(theCall, responseType, retryCount, false);
    }

    public static <T> CompletableFuture<T> call(Call theCall, TypeToken<? extends T> responseType, int retryCount, boolean attemptParsingUnsuccessfulResponse) {
        return call(theCall, responseType, retryCount, attemptParsingUnsuccessfulResponse, ResponseBodyLogging.FULL);
    }

    /// [#call(Call, TypeToken, int)] for an endpoint whose URL carries personal data, such as a vehicle identifier in its path, while its responses carry none;
    /// an unsuccessful response's body still travels in [HttpResponseException].
    ///
    /// @param <T>         the type of the call's value
    /// @param loggableUrl stands in for the URL in every log line and failure message
    public static <T> CompletableFuture<T> call(Call theCall, TypeToken<? extends T> responseType, int retryCount, StringFormattable loggableUrl) {
        return call(theCall, responseType, retryCount, false, ResponseBodyLogging.FULL, checkNotNull(loggableUrl));
    }

    /// [#call(Call, Class, int)] for an endpoint whose responses can carry personal data: the body is kept out of the log line, the parse-failure message and
    /// [HttpResponseException], while the status and the body's length still travel.
    ///
    /// Use it wherever the upstream quotes the submitted value back — a transactional email API naming the recipient it rejected, say.
    ///
    /// @param <T> the type of the call's value
    public static <T> CompletableFuture<T> callSuppressingResponseBody(Call theCall, Class<? extends T> responseType, int retryCount) {
        return call(theCall, TypeToken.of(responseType), retryCount, false, ResponseBodyLogging.SUPPRESSED, null);
    }

    /// [#callSuppressingResponseBody(Call, Class, int)] for an endpoint whose URL itself carries personal data, such as a vehicle identifier in its path:
    /// `loggableUrl` stands in for the URL in every log line and failure message.
    ///
    /// @param <T> the type of the call's value
    public static <T> CompletableFuture<T> callSuppressingResponseBody(Call theCall,
                                                                       TypeToken<? extends T> responseType,
                                                                       int retryCount,
                                                                       StringFormattable loggableUrl) {
        return call(theCall, responseType, retryCount, false, ResponseBodyLogging.SUPPRESSED, checkNotNull(loggableUrl));
    }

    /// Calls as [#callSuppressingResponseBody(Call, TypeToken, int, StringFormattable)] does, except that `parser` turns the body into the call's value.
    ///
    /// @param <T>                                 the type of the call's value
    /// @param parser                              turns a successful response's body into the call's value; a {@value HttpStatuses#NO_CONTENT_204} fails
    ///                                            the call, as it does for a non-[Void] type
    /// @param unsuccessfulResponseDetailExtractor extracts the part of an unsuccessful response's body that [HttpResponseException#body()] carries in place
    ///                                            of the withheld body
    public static <T> CompletableFuture<T> callSuppressingResponseBody(Call theCall,
                                                                       Function<String, ? extends T> parser,
                                                                       int retryCount,
                                                                       StringFormattable loggableUrl,
                                                                       UnsuccessfulResponseDetailExtractor unsuccessfulResponseDetailExtractor) {
        return call(theCall,
                    new ResponseReader<>(parser, false, "a body"),
                    retryCount,
                    false,
                    ResponseBodyLogging.SUPPRESSED,
                    checkNotNull(loggableUrl),
                    checkNotNull(unsuccessfulResponseDetailExtractor));
    }

    private static <T> CompletableFuture<T> call(Call theCall,
                                                 TypeToken<? extends T> responseType,
                                                 int retryCount,
                                                 boolean attemptParsingUnsuccessfulResponse,
                                                 ResponseBodyLogging responseBodyLogging) {
        return call(theCall, responseType, retryCount, attemptParsingUnsuccessfulResponse, responseBodyLogging, null);
    }

    /// @param <T>         the type of the call's value
    /// @param loggableUrl what log lines and failure messages show in place of the request URL, or `null` to show the URL itself
    private static <T> CompletableFuture<T> call(Call theCall,
                                                 TypeToken<? extends T> responseType,
                                                 int retryCount,
                                                 boolean attemptParsingUnsuccessfulResponse,
                                                 ResponseBodyLogging responseBodyLogging,
                                                 @Nullable StringFormattable loggableUrl) {
        return call(theCall,
                    new ResponseReader<>(body -> Json.parse(body, responseType), responseType.getType() == Void.class, responseType),
                    retryCount,
                    attemptParsingUnsuccessfulResponse,
                    responseBodyLogging,
                    loggableUrl,
                    UnsuccessfulResponseDetailExtractor.NONE);
    }

    /// @param <T>         the type of the call's value
    /// @param loggableUrl what log lines and failure messages show in place of the request URL, or `null` to show the URL itself
    private static <T> CompletableFuture<T> call(Call theCall,
                                                 ResponseReader<T> responseReader,
                                                 int retryCount,
                                                 boolean attemptParsingUnsuccessfulResponse,
                                                 ResponseBodyLogging responseBodyLogging,
                                                 @Nullable StringFormattable loggableUrl,
                                                 UnsuccessfulResponseDetailExtractor unsuccessfulResponseDetailExtractor) {
        int requestId = requestIdGenerator.incrementAndGet();
        CompletableFuture<T> future = new CompletableFuture<>();
        logger.debug("[{}] Sending {} {}", requestId, theCall.request().method(), loggableUrl == null ? theCall.request().url() : loggableUrl);
        theCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Method and URL, never the Request itself: its toString renders the headers, and OkHttp redacts only the well-known credential ones
                // (Authorization, Cookie, Proxy-Authorization, Set-Cookie). An API passing its key in a header of its own choosing — `api-key`, `X-Api-Key` —
                // would otherwise put that key in this message, which reaches WARN logs and operator alerts.
                String url = loggableUrl == null ? call.request().url().toString() : loggableUrl.toString(128);
                logger.debug("[{}] Call failed: {} {}, retries left: {}", requestId, call.request().method(), url, retryCount, e);
                if (retryCount == 0) {
                    future.completeExceptionally(createCallFailure(call, url, e));
                } else {
                    // attemptParsingUnsuccessfulResponse, responseBodyLogging and loggableUrl are carried into the retry. Dropping one here would silently
                    // restore body or URL logging (or unsuccessful-response parsing) for every attempt after the first, which is precisely when a flaky
                    // endpoint is being retried.
                    call(call.clone(),
                         responseReader,
                         retryCount - 1,
                         attemptParsingUnsuccessfulResponse,
                         responseBodyLogging,
                         loggableUrl,
                         unsuccessfulResponseDetailExtractor)
                            .whenComplete((result, exception) -> {
                                if (exception == null) {
                                    future.complete(result);
                                } else {
                                    future.completeExceptionally(exception);
                                }
                            });
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    try {
                        if (response.isSuccessful()) {
                            if (response.code() == NO_CONTENT_204) { // no body
                                logger.debug("[{}] Response code 204", requestId);
                                if (responseReader.acceptsNoContent()) {
                                    future.complete(null);
                                } else {
                                    future.completeExceptionally(new RuntimeException(
                                            "Response is successful but empty, however expected response type is " + responseReader.expectedBodyDescription()));
                                }
                            } else {
                                // read whole, since logResponse and every ResponseReader parser take the body as one String
                                String responseString = requireNonNull(responseBody).string();
                                logResponse(requestId, response.code(), responseString);
                                parseAndCompleteFuture(responseString);
                            }
                        } else {
                            String responseString = safelyToString(responseBody);
                            logResponse(requestId, response.code(), responseString);
                            // A rejected credential is never a domain response, whatever the endpoint's error envelope looks like, so it is reported as the
                            // status rather than parsed — otherwise it reaches the caller as an ordinary value and nothing can tell it apart from a refusal
                            // the credential could survive.
                            if (attemptParsingUnsuccessfulResponse && response.code() != UNAUTHORIZED_401) {
                                parseAndCompleteFuture(responseString);
                            } else {
                                // The status is kept, since SharedUpstreamOutage classifies retryability from it. On a suppressed endpoint the body, which can
                                // quote the offending value back, is replaced by whatever unsuccessfulResponseDetailExtractor extracts.
                                future.completeExceptionally(new HttpResponseException(response.code(), switch (responseBodyLogging) {
                                    case FULL -> responseString;
                                    case SUPPRESSED -> unsuccessfulResponseDetailExtractor.extract(response.code(), responseString)
                                                                                          .orElse(WITHHELD_BODY);
                                }));
                            }
                        }
                    } catch (RuntimeException | IOException e) {
                        future.completeExceptionally(new RuntimeException("failed to process response body", e));
                    }
                }
            }

            private void logResponse(int id, int statusCode, String responseString) {
                if (!logger.isDebugEnabled()) {
                    return;
                }
                // An expression switch, so a third logging mode is a compile error here rather than a body silently logged verbatim.
                Object body = switch (responseBodyLogging) {
                    case FULL -> responseString;
                    case SUPPRESSED -> responseString.length() + " chars, body withheld: may carry personal data";
                };
                logger.debug("[{}] Response code {}: {}", id, statusCode, body);
            }

            private void parseAndCompleteFuture(String responseString) {
                T responseData;
                try {
                    responseData = responseReader.parser().apply(responseString);
                    future.complete(responseData);
                } catch (RuntimeException e) {
                    // The unparseable body is the whole diagnosis, so it is normally in the message — but on a suppressed endpoint it is the one thing that
                    // must not be, and this message travels into an exception that is logged at ERROR rather than DEBUG.
                    future.completeExceptionally(switch (responseBodyLogging) {
                        case FULL -> new RuntimeException("Failed parsing response " + responseString, e);
                        case SUPPRESSED -> new RuntimeException("Failed parsing response of " + responseString.length() + " chars", e);
                    });
                }
            }

            private static String safelyToString(ResponseBody responseBody) {
                try {
                    return responseBody.string();
                } catch (IOException | RuntimeException e) {
                    return "<failed to read body: " + humanReadableMessage(e) + ">";
                }
            }
        });
        return future;
    }

    /// Creates the exception a call that got no response fails with: its message names the request method and `loggableUrl`, and its cause is `cause`.
    ///
    /// @param loggableUrl what the message shows for the request URL, which must carry no personal data
    public static RuntimeException createCallFailure(Call call, String loggableUrl, IOException cause) {
        return new RuntimeException("call failed: " + call.request().method() + ' ' + checkNotNull(loggableUrl), checkNotNull(cause));
    }

    /// Generic page-following fetch for REST endpoints that return `{ "results": [...], "next": "<url>" }`-shaped pages. Issues a GET via `callFactory` for
    /// each URL in turn, deserialises the body as `pageType`, appends `results(page)` to a single shared [ImmutableList.Builder], then recurses into
    /// `nextUrl(page)` if present.
    ///
    /// @param <PageT>            the type each page's body deserialises to
    /// @param <T>                the type of the results each page lists
    /// @param expectedTotalCount non-binding sizing hint passed to [ImmutableList#builderWithExpectedSize] to pre-allocate the accumulator and avoid
    ///                           growth-time copies. Over- or under-estimating is harmless. Compute from whatever domain knowledge the caller has — slot
    ///                           count from a time range, typical catalogue size, etc.
    public static <PageT, T> CompletableFuture<List<T>> paginate(Function<String, Call> callFactory,
                                                                 String firstUrl,
                                                                 int expectedTotalCount,
                                                                 TypeToken<PageT> pageType,
                                                                 Function<PageT, ? extends List<T>> resultsAccessor,
                                                                 Function<PageT, Optional<String>> nextUrlAccessor) {
        checkArgument(expectedTotalCount >= 0, "expectedTotalCount must be non-negative");
        var accumulator = ImmutableList.<T>builderWithExpectedSize(expectedTotalCount);
        return forEachPage(url -> call(callFactory.apply(url), pageType),
                           firstUrl,
                           page -> accumulator.addAll(resultsAccessor.apply(page)),
                           nextUrlAccessor)
                .thenApply(_ -> accumulator.build());
    }

    /// Fetches a paged listing one page at a time, handing each page to `pageConsumer` in server order, until `nextCursorAccessor` returns empty.
    ///
    /// @param <C>                the type of the cursor that names a page, such as its URL or a page token
    /// @param <PageT>            the type of a page
    /// @param pageFetcher        fetches the page a cursor names, choosing how the call is made and logged
    /// @param pageConsumer       called with every page, the last one included, before the next is fetched; never concurrently, each call happening-before
    ///                           the next, so it may write non-concurrent state
    /// @param nextCursorAccessor the cursor of the page after the given one, or empty on the last page
    /// @return the last page
    public static <C, PageT> CompletableFuture<PageT> forEachPage(Function<? super C, CompletableFuture<PageT>> pageFetcher,
                                                                  C firstCursor,
                                                                  Consumer<? super PageT> pageConsumer,
                                                                  Function<? super PageT, Optional<C>> nextCursorAccessor) {
        return pageFetcher.apply(firstCursor).thenCompose(page -> {
            pageConsumer.accept(page);
            return nextCursorAccessor.apply(page)
                                     .map(cursor -> forEachPage(pageFetcher, cursor, pageConsumer, nextCursorAccessor))
                                     .orElseGet(() -> CompletableFuture.completedFuture(page));
        });
    }

    public static JsonNode getRequiredNode(JsonNode parentNode, String nodeName) {
        JsonNode childNode = parentNode.get(nodeName);
        checkState(childNode != null,
                   "no '%s' node in response: %s", nodeName, parentNode);
        return childNode;
    }

    public static String getRequiredNodeString(JsonNode parentNode, String nodeName) {
        JsonNode childNode = getRequiredNode(parentNode, nodeName);
        checkState(childNode.isTextual(), "node '%s' is not textual in %s", nodeName, parentNode);
        return childNode.asText();
    }

    public static int getRequiredNodeInt(JsonNode parentNode, String nodeName) {
        JsonNode childNode = getRequiredNode(parentNode, nodeName);
        checkState(childNode.isInt(), "node '%s' is not an integer in %s", nodeName, parentNode);
        return childNode.asInt();
    }

    public static long getRequiredNodeLong(JsonNode parentNode, String nodeName) {
        JsonNode childNode = getRequiredNode(parentNode, nodeName);
        checkState(childNode.isLong(), "node '%s' is not a long in %s", nodeName, parentNode);
        return childNode.asLong();
    }

    public static void shutdown(OkHttpClient client) {
        shutdown(client, Duration.ofSeconds(10));
    }

    public static void shutdown(OkHttpClient client, Duration timeout) {
        try {
            logger.debug("Shutting down {}", client);
            MoreExecutors.shutdownAndAwaitTermination(client.dispatcher().executorService(), timeout);
            client.connectionPool().evictAll();
            closeSafelyIfNotNull(logger, client.cache());
        } catch (RuntimeException e) {
            logger.warn("Failed to gracefully shut down client {} in {}", client, timeout, e);
        }
    }

    /// Shuts `client` down without waiting for its dispatcher to finish, failing every call in flight, and every call enqueued afterwards, with an
    /// [IOException].
    public static void shutdownNow(OkHttpClient client) {
        try {
            logger.debug("Shutting down {} now", client);
            // Shut first, so a call enqueued from a cancelled call's callback is rejected rather than run.
            client.dispatcher().executorService().shutdown();
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
            closeSafelyIfNotNull(logger, client.cache());
        } catch (RuntimeException e) {
            logger.info("Failed to shut down client {}", client, e);
        }
    }

    /// How a successful response's body becomes a call's value.
    ///
    /// @param <T>                     the type of the call's value
    /// @param parser                  turns the body into the call's value
    /// @param acceptsNoContent        whether a {@value HttpStatuses#NO_CONTENT_204} completes the call with `null` rather than failing it
    /// @param expectedBodyDescription names what the body was expected to hold, for the failure a {@value HttpStatuses#NO_CONTENT_204} otherwise causes
    private record ResponseReader<T>(Function<String, ? extends T> parser, boolean acceptsNoContent, Object expectedBodyDescription) {
        private ResponseReader {
            checkNotNull(parser);
            checkNotNull(expectedBodyDescription);
        }
    }

    /// Whether a response body may be reproduced in log lines and failure messages.
    private enum ResponseBodyLogging {
        /// The body is logged verbatim at DEBUG and carried in [HttpResponseException#body()], which is what makes a failed call diagnosable.
        FULL,
        /// The body is replaced by its length wherever it would otherwise be reproduced. For upstreams that quote a submitted value back — the recipient a
        /// transactional email API rejected, say — where a raised log level would otherwise be enough to spill personal data into the log file.
        SUPPRESSED
    }
}
