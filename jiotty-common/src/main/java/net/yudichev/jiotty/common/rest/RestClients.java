package net.yudichev.jiotty.common.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.common.util.concurrent.MoreExecutors;
import net.yudichev.jiotty.common.lang.Json;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;

public final class RestClients {
    private static final Logger logger = LogManager.getLogger(RestClients.class);

    private static final int DEFAULT_CALL_RETRY_COUNT = 3;
    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(60);
    private static final AtomicInteger requestIdGenerator = new AtomicInteger();

    private RestClients() {
    }

    public static OkHttpClient newClient() {
        return newClient(_ -> {});
    }

    public static OkHttpClient newClient(Consumer<? super OkHttpClient.Builder> customizer) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(DEFAULT_HTTP_TIMEOUT)
                .callTimeout(DEFAULT_HTTP_TIMEOUT)
                .readTimeout(DEFAULT_HTTP_TIMEOUT)
                .writeTimeout(DEFAULT_HTTP_TIMEOUT);
        customizer.accept(builder);
        return builder.build();
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

    /// [#call(Call, Class, int)] for an endpoint whose responses can carry personal data: the body is kept out of the log line, the parse-failure message and
    /// [HttpResponseException], while the status and the body's length still travel.
    ///
    /// Use it wherever the upstream quotes the submitted value back — a transactional email API naming the recipient it rejected, say.
    public static <T> CompletableFuture<T> callSuppressingResponseBody(Call theCall, Class<? extends T> responseType, int retryCount) {
        return call(theCall, TypeToken.of(responseType), retryCount, false, ResponseBodyLogging.SUPPRESSED);
    }

    private static <T> CompletableFuture<T> call(Call theCall,
                                                 TypeToken<? extends T> responseType,
                                                 int retryCount,
                                                 boolean attemptParsingUnsuccessfulResponse,
                                                 ResponseBodyLogging responseBodyLogging) {
        int requestId = requestIdGenerator.incrementAndGet();
        CompletableFuture<T> future = new CompletableFuture<>();
        logger.debug("[{}] Sending {} {}", requestId, theCall.request().method(), theCall.request().url());
        theCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Method and URL, never the Request itself: its toString renders the headers, and OkHttp redacts only the well-known credential ones
                // (Authorization, Cookie, Proxy-Authorization, Set-Cookie). An API passing its key in a header of its own choosing — `api-key`, `X-Api-Key` —
                // would otherwise put that key in this message, which reaches WARN logs and operator alerts.
                logger.debug("[{}] Call failed: {} {}, retries left: {}", requestId, call.request().method(), call.request().url(), retryCount, e);
                if (retryCount == 0) {
                    future.completeExceptionally(new RuntimeException("call failed: " + call.request().method() + ' ' + call.request().url(), e));
                } else {
                    // Both flags are carried into the retry. Dropping them here would silently restore body logging (and unsuccessful-response parsing) for
                    // every attempt after the first, which is precisely when a flaky endpoint is being retried.
                    call(call.clone(), responseType, retryCount - 1, attemptParsingUnsuccessfulResponse, responseBodyLogging)
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
                                if (responseType.getType() == Void.class) {
                                    future.complete(null);
                                } else {
                                    future.completeExceptionally(new RuntimeException(
                                            "Response is successful but empty, however expected response type is " + responseType));
                                }
                            } else {
                                String responseString = requireNonNull(responseBody).string();
                                logResponse(requestId, response.code(), responseString);
                                parseAndCompleteFuture(responseString);
                            }
                        } else {
                            String responseString = safelyToString(responseBody);
                            logResponse(requestId, response.code(), responseString);
                            if (attemptParsingUnsuccessfulResponse) {
                                parseAndCompleteFuture(responseString);
                            } else {
                                // The status is kept — SharedUpstreamOutage classifies retryability from it — while the body, which is what an upstream uses
                                // to quote the offending value back, is withheld on a suppressed endpoint.
                                future.completeExceptionally(new HttpResponseException(response.code(), switch (responseBodyLogging) {
                                    case FULL -> responseString;
                                    case SUPPRESSED -> "<withheld: may carry personal data>";
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
                    responseData = Json.parse(responseString, responseType);
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

    /// Generic page-following fetch for REST endpoints that return `{ "results": [...], "next": "<url>" }`-shaped pages. Issues a GET via `callFactory` for
    /// each URL in turn, deserialises the body as `pageType`, appends `results(page)` to a single shared [ImmutableList.Builder], then recurses into
    /// `nextUrl(page)` if present.
    ///
    /// @param expectedTotalCount non-binding sizing hint passed to [ImmutableList#builderWithExpectedSize] to pre-allocate the accumulator and avoid
    /// growth-time copies. Over- or under-estimating is harmless. Compute from whatever domain knowledge the caller has — slot count from a time range, typical
    /// catalogue size, etc.
    /// @implNote Threading: appends to the accumulator are sequenced by [CompletableFuture#thenCompose]'s happens-before edges, so the non-concurrent
    /// [ImmutableList.Builder] is safe even though OkHttp's dispatcher may deliver successive pages on different worker threads — at any moment only one
    /// continuation is executing for a given chain.
    public static <PageT, T> CompletableFuture<List<T>> paginate(Function<String, Call> callFactory,
                                                                 String firstUrl,
                                                                 int expectedTotalCount,
                                                                 TypeToken<PageT> pageType,
                                                                 Function<PageT, ? extends List<T>> resultsAccessor,
                                                                 Function<PageT, Optional<String>> nextUrlAccessor) {
        checkArgument(expectedTotalCount >= 0, "expectedTotalCount must be non-negative");
        var accumulator = ImmutableList.<T>builderWithExpectedSize(expectedTotalCount);
        return paginateInto(callFactory, firstUrl, pageType, resultsAccessor, nextUrlAccessor, accumulator)
                .thenApply(_ -> accumulator.build());
    }

    private static <PageT, T> CompletableFuture<Void> paginateInto(Function<String, Call> callFactory,
                                                                   String url,
                                                                   TypeToken<PageT> pageType,
                                                                   Function<PageT, ? extends List<T>> resultsAccessor,
                                                                   Function<PageT, Optional<String>> nextUrlAccessor,
                                                                   ImmutableList.Builder<T> accumulator) {
        return call(callFactory.apply(url), pageType).thenCompose(page -> {
            accumulator.addAll(resultsAccessor.apply(page));
            return nextUrlAccessor.apply(page)
                                  .map(nextUrl -> paginateInto(callFactory, nextUrl, pageType, resultsAccessor, nextUrlAccessor, accumulator))
                                  .orElseGet(() -> CompletableFuture.completedFuture(null));
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

    /// Whether a response body may be reproduced in log lines and failure messages.
    private enum ResponseBodyLogging {
        /// The body is logged verbatim at DEBUG and carried in [HttpResponseException#body()], which is what makes a failed call diagnosable.
        FULL,
        /// The body is replaced by its length wherever it would otherwise be reproduced. For upstreams that quote a submitted value back — the recipient a
        /// transactional email API rejected, say — where a raised log level would otherwise be enough to spill personal data into the log file.
        SUPPRESSED
    }
}
