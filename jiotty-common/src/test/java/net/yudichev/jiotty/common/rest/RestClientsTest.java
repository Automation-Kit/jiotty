package net.yudichev.jiotty.common.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.misc.SharedUpstreamOutage;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpClientAssertions.assertGivesUpAfter;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.OPTIONAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class RestClientsTest {

    private static final String BASE = "http://example.test";
    private static final String VIN = "5YJ3E1EA1JF000001";
    private static final String VIN_URL = BASE + "/vehicles/" + VIN + "/config";
    private static final StringFormattable REDACTED_URL = appendable -> Append.to(appendable, "redacted");
    /// Stands for the personal data an upstream quotes back in an error body, pseudonymous because production logs the failures it handles.
    private static final String PERSONAL_MARKER = "user-7f3a9c";
    private static final InetAddress LOOPBACK = InetAddresses.forString("127.0.0.1");
    private final Map<String, String> urlToBody = new HashMap<>();
    /// Overrides the default HTTP 200 for a given URL. Tests asserting non-2xx behaviour put the status code here and the error payload in [#urlToBody] under
    /// the same URL key; the [#setUp] dispatcher composes both into a non-2xx [Response].
    private final Map<String, Integer> urlToHttpStatus = new HashMap<>();
    private final Map<String, Integer> urlHitCount = new HashMap<>();
    /// URLs that should be answered with an IOException instead of a body.
    private final Map<String, IOException> urlToFailure = new HashMap<>();
    /// How many attempts at a URL in [#urlToFailure] fail before it is answered from [#urlToBody]; a URL absent here fails every attempt.
    private final Map<String, Integer> failingAttemptCountsByUrl = new HashMap<>();
    private OkHttpClient httpClient;
    private Function<String, Call> callFactory;
    private @Nullable OkHttpClient realClient;
    private @Nullable ServerSocket silentServer;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        lenient().when(httpClient.newCall(any())).thenAnswer(inv -> {
            Request request = inv.getArgument(0);
            Call callMock = mock(Call.class);
            lenient().when(callMock.request()).thenReturn(request);
            lenient().when(callMock.clone()).thenReturn(callMock);
            lenient().doAnswer(enq -> {
                Callback cb = enq.getArgument(0);
                String url = request.url().toString();
                int hitCount = urlHitCount.merge(url, 1, Integer::sum);
                IOException failure = urlToFailure.get(url);
                if (failure != null && hitCount <= failingAttemptCountsByUrl.getOrDefault(url, Integer.MAX_VALUE)) {
                    cb.onFailure(callMock, failure);
                } else {
                    String body = urlToBody.get(url);
                    if (body != null) {
                        int status = urlToHttpStatus.getOrDefault(url, OK_200);
                        cb.onResponse(callMock, response(request, status, body));
                    }
                    // unstubbed URL: leave pending — tests that miss-spec a URL will time out, surfacing the bug
                }
                return null;
            }).when(callMock).enqueue(any());
            return callMock;
        });
        callFactory = url -> httpClient.newCall(new Request.Builder().url(url).get().build());
    }

    @AfterEach
    void tearDown() {
        if (realClient != null) {
            RestClients.shutdownNow(realClient);
        }
        closeIfNotNull(silentServer);
    }

    @Test
    void paginate_singlePage_returnsItemsFromThatPage() {
        urlToBody.put(BASE + "/items?p=1",
                      """
                      {"results": [{"name": "alpha"}, {"name": "beta"}], "next": null}
                      """);

        List<Item> items = RestClients.paginate(
                callFactory, BASE + "/items?p=1", 2,
                new TypeToken<>() {}, Page::results, Page::nextUrl).join();

        assertThat(items).extracting(Item::name).containsExactly("alpha", "beta");
    }

    @Test
    void paginate_multiplePages_concatenatesInServerOrder() {
        urlToBody.put(BASE + "/items?p=1", """
                                           {"results": [{"name": "a"}], "next": "%s/items?p=2"}
                                           """.formatted(BASE));
        urlToBody.put(BASE + "/items?p=2", """
                                           {"results": [{"name": "b"}, {"name": "c"}], "next": "%s/items?p=3"}
                                           """.formatted(BASE));
        urlToBody.put(BASE + "/items?p=3", """
                                           {"results": [{"name": "d"}], "next": null}
                                           """);

        List<Item> items = RestClients.paginate(
                callFactory, BASE + "/items?p=1", 4,
                new TypeToken<>() {}, Page::results, Page::nextUrl).join();

        assertThat(items).extracting(Item::name).containsExactly("a", "b", "c", "d");
    }

    @Test
    void paginate_eachPageFetchedExactlyOnce_noQuadraticReads() {
        // Sanity check on the accumulator design: even with N pages, each page URL is hit exactly once. A "tail-concat" implementation could pass the
        // results-shape test above but re-fetch a page on each recursion; this test pins that down.
        urlToBody.put(BASE + "/a", """
                                   {"results": [{"name": "1"}], "next": "%s/b"}
                                   """.formatted(BASE));
        urlToBody.put(BASE + "/b", """
                                   {"results": [{"name": "2"}], "next": "%s/c"}
                                   """.formatted(BASE));
        urlToBody.put(BASE + "/c", """
                                   {"results": [{"name": "3"}], "next": null}
                                   """);

        RestClients.paginate(callFactory, BASE + "/a", 3,
                             new TypeToken<>() {}, Page::results, Page::nextUrl).join();

        assertThat(urlHitCount).containsOnly(Map.entry(BASE + "/a", 1),
                                             Map.entry(BASE + "/b", 1),
                                             Map.entry(BASE + "/c", 1));
    }

    @Test
    void paginate_emptyResults_returnsEmptyList() {
        urlToBody.put(BASE + "/empty", """
                                       {"results": [], "next": null}
                                       """);

        List<Item> items = RestClients.paginate(
                callFactory, BASE + "/empty", 0,
                new TypeToken<>() {}, Page::results, Page::nextUrl).join();

        assertThat(items).isEmpty();
    }

    @Test
    void paginate_failureMidChain_propagatesAsFailedFuture() {
        urlToBody.put(BASE + "/p1", """
                                    {"results": [{"name": "a"}], "next": "%s/p2"}
                                    """.formatted(BASE));
        // p2 fails with an IOException — RestClients.call retries DEFAULT_CALL_RETRY_COUNT times, all of which fail the same way; the final future fails.
        urlToFailure.put(BASE + "/p2", new IOException("transport boom"));

        CompletableFuture<List<Item>> future = RestClients.paginate(
                callFactory, BASE + "/p1", 2,
                new TypeToken<>() {}, Page::results, Page::nextUrl);

        assertThatThrownBy(future::join).hasRootCauseMessage("transport boom");
    }

    @Test
    void paginate_returnedListIsImmutable() {
        // Per the java-style rule: the formal return type is `List`, but the underlying instance must be frozen.
        urlToBody.put(BASE + "/x", """
                                   {"results": [{"name": "x"}], "next": null}
                                   """);

        List<Item> items = RestClients.paginate(
                callFactory, BASE + "/x", 1,
                new TypeToken<>() {}, Page::results, Page::nextUrl).join();

        assertThat(items).isInstanceOf(ImmutableList.class);
        assertThatThrownBy(() -> items.add(new Item("mutator"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void paginate_accessorFunctionsInvokedOncePerPage() {
        // Accumulator-style helpers can accidentally drive the accessor functions twice per page (once during fetch, once during fold). The contract is
        // exactly once.
        var resultsCalls = new AtomicInteger();
        var nextUrlCalls = new AtomicInteger();
        urlToBody.put(BASE + "/q1", """
                                    {"results": [{"name": "a"}], "next": "%s/q2"}
                                    """.formatted(BASE));
        urlToBody.put(BASE + "/q2", """
                                    {"results": [{"name": "b"}], "next": null}
                                    """);

        RestClients.paginate(callFactory, BASE + "/q1", 2, new TypeToken<Page>() {},
                             page -> {
                                 resultsCalls.incrementAndGet();
                                 return page.results();
                             },
                             page -> {
                                 nextUrlCalls.incrementAndGet();
                                 return page.nextUrl();
                             }).join();

        assertThat(resultsCalls).hasValue(2);
        assertThat(nextUrlCalls).hasValue(2);
    }

    @Test
    void call_nonSuccessResponse_throwsHttpResponseExceptionCarryingStatusAndBody() {
        urlToBody.put(BASE + "/forbidden", "{\"detail\":\"forbidden\"}");
        urlToHttpStatus.put(BASE + "/forbidden", FORBIDDEN_403);

        CompletableFuture<Page> future = RestClients.call(callFactory.apply(BASE + "/forbidden"), new TypeToken<>() {});

        assertThatThrownBy(future::join)
                .cause()
                .isInstanceOfSatisfying(HttpResponseException.class, http -> {
                    assertThat(http.statusCode()).isEqualTo(FORBIDDEN_403);
                    assertThat(http.body()).isEqualTo("{\"detail\":\"forbidden\"}");
                    // Message format preserved verbatim from the previous untyped form so any string-matching callers keep working.
                    assertThat(http).hasMessageContaining("Response code 403").hasMessageContaining("forbidden");
                });
    }

    /// Some upstreams report domain refusals in an error envelope the caller wants parsed, which is what the parsing overload is for. A rejected credential is
    /// never one of those: it has to reach the caller as a status, or nothing can tell it apart from a refusal the credential could survive.
    @Test
    void call_parsingUnsuccessfulResponses_stillThrowsOnARejectedCredential() {
        urlToBody.put(BASE + "/unauthorised", "{\"error\":\"token expired\"}");
        urlToHttpStatus.put(BASE + "/unauthorised", UNAUTHORIZED_401);

        CompletableFuture<Page> future = RestClients.call(callFactory.apply(BASE + "/unauthorised"), new TypeToken<>() {}, 0, true);

        assertThatThrownBy(future::join)
                .cause()
                .isInstanceOfSatisfying(HttpResponseException.class, http -> {
                    assertThat(http.statusCode()).isEqualTo(UNAUTHORIZED_401);
                    assertThat(http.body()).isEqualTo("{\"error\":\"token expired\"}");
                });
    }

    /// Every other unsuccessful status keeps the parsing behaviour the flag asks for.
    @Test
    void call_parsingUnsuccessfulResponses_stillParsesANonAuthError() {
        urlToBody.put(BASE + "/refused", """
                                         {"results": [{"name": "refused"}], "next": null}""");
        urlToHttpStatus.put(BASE + "/refused", FORBIDDEN_403);

        CompletableFuture<Page> future = RestClients.call(callFactory.apply(BASE + "/refused"), new TypeToken<>() {}, 0, true);

        assertThat(future.join().results()).singleElement().satisfies(item -> assertThat(item.name()).isEqualTo("refused"));
    }

    /// The whole point of the suppressed variant: an upstream that quotes the submitted value back must not have that value reproduced in the exception, which
    /// a caller turns into an operator alert. The status has to survive, because [SharedUpstreamOutage] classifies retryability from it.
    @Test
    void callSuppressingResponseBody_nonSuccessResponse_keepsTheStatusAndWithholdsTheBody() {
        urlToBody.put(BASE + "/rejected", "{\"message\":\"someone@example.com is not valid\"}");
        urlToHttpStatus.put(BASE + "/rejected", FORBIDDEN_403);

        CompletableFuture<Page> future = RestClients.callSuppressingResponseBody(callFactory.apply(BASE + "/rejected"), Page.class, 0);

        assertThatThrownBy(future::join)
                .cause()
                .isInstanceOfSatisfying(HttpResponseException.class, http -> {
                    assertThat(http.statusCode()).isEqualTo(FORBIDDEN_403);
                    assertThat(http.body()).doesNotContain("someone@example.com");
                    assertThat(http).hasMessageContaining("Response code 403").hasMessageNotContaining("someone@example.com");
                });
    }

    /// Suppression is about what is *reproduced*, not about what is parsed: a successful response is still deserialised normally.
    @Test
    void callSuppressingResponseBody_successfulResponse_stillParsesTheBody() {
        urlToBody.put(BASE + "/ok", """
                                    {"results": [{"name": "alpha"}], "next": null}
                                    """);

        assertThat(RestClients.callSuppressingResponseBody(callFactory.apply(BASE + "/ok"), Page.class, 0).join().results())
                .extracting(Item::name)
                .containsExactly("alpha");
    }

    /// Suppression must survive a retry. It is carried in the recursive call, because an attempt after the first is exactly when a flaky endpoint is being
    /// hit — and the flag silently reverting there would spill the body on precisely the calls most likely to fail.
    @Test
    void callSuppressingResponseBody_survivesARetry() {
        urlToFailure.put(BASE + "/flaky", new IOException("transport boom"));

        assertThatThrownBy(() -> RestClients.callSuppressingResponseBody(callFactory.apply(BASE + "/flaky"), Page.class, 2).join())
                .hasRootCauseMessage("transport boom");
        // One initial attempt plus the two retries, each of which had to carry the suppression with it.
        assertThat(urlHitCount).containsEntry(BASE + "/flaky", 3);
    }

    /// A body that fails to parse is normally quoted in the failure message, which is the one place a suppressed endpoint's payload must still not appear —
    /// this message travels in an exception logged at ERROR, not at DEBUG.
    @Test
    void callSuppressingResponseBody_unparseableBody_withholdsItFromTheFailureMessage() {
        urlToBody.put(BASE + "/garbage", "not json: someone@example.com");

        CompletableFuture<Page> future = RestClients.callSuppressingResponseBody(callFactory.apply(BASE + "/garbage"), Page.class, 0);

        assertThatThrownBy(future::join)
                .cause()
                .hasMessageNotContaining("someone@example.com")
                .hasMessageContaining("chars");
    }

    /// The VIN-shaped path segment is what the PII log guard looks for, so the guard also fails this test if a log line renders the real URL.
    @Test
    void callSuppressingResponseBody_withLoggableUrl_keepsTheRealUrlOutOfTheFailureAcrossRetries() {
        urlToFailure.put(VIN_URL, new IOException("transport boom"));

        CompletableFuture<Page> future = RestClients.callSuppressingResponseBody(callFactory.apply(VIN_URL),
                                                                                 TypeToken.of(Page.class),
                                                                                 1,
                                                                                 appendable -> Append.to(appendable, BASE + "/vehicles/5YJ***/config"));

        assertThatThrownBy(future::join).cause()
                                        .hasMessage("call failed: GET " + BASE + "/vehicles/5YJ***/config")
                                        .hasMessageNotContaining(VIN);
        assertThat(urlHitCount).containsEntry(VIN_URL, 2);
    }

    @Test
    void callSuppressingResponseBody_withLoggableUrl_nonSuccessResponse_keepsTheStatusAndWithholdsTheBody() {
        urlToBody.put(VIN_URL, "{\"error\":\"vehicle " + VIN + " not found\"}");
        urlToHttpStatus.put(VIN_URL, NOT_FOUND_404);

        CompletableFuture<Page> future = RestClients.callSuppressingResponseBody(callFactory.apply(VIN_URL),
                                                                                 TypeToken.of(Page.class),
                                                                                 0,
                                                                                 REDACTED_URL);

        assertThatThrownBy(future::join)
                .cause()
                .isInstanceOfSatisfying(HttpResponseException.class, http -> {
                    assertThat(http.statusCode()).isEqualTo(NOT_FOUND_404);
                    assertThat(http).hasMessageNotContaining(VIN);
                });
    }

    /// The VIN-shaped path segment is what the PII log guard looks for, so the guard also fails this test if a log line renders the real URL.
    @Test
    void callWithLoggableUrl_keepsTheRealUrlOutOfTheFailure() {
        urlToFailure.put(VIN_URL, new IOException("transport boom"));

        CompletableFuture<Page> future = RestClients.call(callFactory.apply(VIN_URL), TypeToken.of(Page.class), 1, REDACTED_URL);

        assertThatThrownBy(future::join).cause().hasMessage("call failed: GET redacted");
        assertThat(urlHitCount).containsEntry(VIN_URL, 2);
    }

    @Test
    void callWithLoggableUrl_nonSuccessResponse_keepsTheStatusAndTheBody() {
        urlToBody.put(VIN_URL, "{\"error\":\"vehicle unavailable\"}");
        urlToHttpStatus.put(VIN_URL, NOT_FOUND_404);

        CompletableFuture<Page> future = RestClients.call(callFactory.apply(VIN_URL), TypeToken.of(Page.class), 0, REDACTED_URL);

        assertThatThrownBy(future::join)
                .cause()
                .isInstanceOfSatisfying(HttpResponseException.class, http -> {
                    assertThat(http.statusCode()).isEqualTo(NOT_FOUND_404);
                    assertThat(http.body()).isEqualTo("{\"error\":\"vehicle unavailable\"}");
                });
    }

    /// Runs a real client against a socket that accepts the connection and never sends a response, so the first call is still in flight when the client shuts
    /// down.
    @Test
    void shutdownNow_failsTheCallsInFlightAndEnqueuedAfterwardsAndStopsTheDispatcher() throws IOException {
        OkHttpClient client = RestClients.newClient();
        realClient = client;
        var server = new ServerSocket(0, 1, LOOPBACK);
        silentServer = server;
        HttpUrl url = new HttpUrl.Builder().scheme("http").host(LOOPBACK.getHostAddress()).port(server.getLocalPort()).build();
        CompletableFuture<Response> callInFlight = enqueue(client, url);

        RestClients.shutdownNow(client);
        CompletableFuture<Response> callEnqueuedAfterwards = enqueue(client, url);

        assertThat(client.dispatcher().executorService().isShutdown()).isTrue();
        assertThat(callInFlight).failsWithin(Duration.ofSeconds(10)).withThrowableThat().withCauseInstanceOf(IOException.class);
        assertThat(callEnqueuedAfterwards).failsWithin(Duration.ofSeconds(10)).withThrowableThat().withCauseInstanceOf(IOException.class);
    }

    private static CompletableFuture<Response> enqueue(OkHttpClient client, HttpUrl url) {
        var response = new CompletableFuture<Response>();
        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                response.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response okResponse) {
                response.complete(okResponse);
            }
        });
        return response;
    }

    @Test
    void newClient_withTimeout_appliesItToEveryKnob() {
        Duration timeout = Duration.ofSeconds(7);

        OkHttpClient client = RestClients.newClient(timeout);

        assertGivesUpAfter(client, timeout);
    }

    @Test
    void callSuppressingResponseBody_withParser_completesWithWhatTheParserMakesOfTheBody() {
        urlToBody.put(BASE + "/text", "<multistatus/>");

        CompletableFuture<Integer> future = RestClients.callSuppressingResponseBody(callFactory.apply(BASE + "/text"),
                                                                                    String::length,
                                                                                    0,
                                                                                    REDACTED_URL,
                                                                                    UnsuccessfulResponseDetailExtractor.NONE);

        assertThat(future).succeedsWithin(Duration.ZERO).isEqualTo("<multistatus/>".length());
    }

    @Test
    void call_voidResponseType_noContent_completesWithNull() {
        urlToBody.put(BASE + "/deleted", "");
        urlToHttpStatus.put(BASE + "/deleted", NO_CONTENT_204);

        assertThat(RestClients.call(callFactory.apply(BASE + "/deleted"), Void.class)).succeedsWithin(Duration.ZERO).isNull();
    }

    @Test
    void callSuppressingResponseBody_withParser_noContent_fails() {
        urlToBody.put(BASE + "/empty", "");
        urlToHttpStatus.put(BASE + "/empty", NO_CONTENT_204);

        CompletableFuture<String> future = RestClients.callSuppressingResponseBody(callFactory.apply(BASE + "/empty"),
                                                                                   Function.identity(),
                                                                                   0,
                                                                                   REDACTED_URL,
                                                                                   UnsuccessfulResponseDetailExtractor.NONE);

        assertThat(future).failsWithin(Duration.ZERO).withThrowableThat().withMessageContaining("successful but empty");
    }

    /// The detail stands in for the body, so a caller can classify the failure by an error code while the rest of the body — which quotes the submitted
    /// value back — stays out of the exception.
    @Test
    void callSuppressingResponseBody_withUnsuccessfulResponseDetail_carriesTheDetailInPlaceOfTheBody() {
        urlToBody.put(BASE + "/limited", "{\"reason\":\"rateLimitExceeded\",\"message\":\"" + PERSONAL_MARKER + " is over quota\"}");
        urlToHttpStatus.put(BASE + "/limited", FORBIDDEN_403);

        CompletableFuture<String> future = RestClients.callSuppressingResponseBody(
                callFactory.apply(BASE + "/limited"),
                Function.identity(),
                0,
                REDACTED_URL,
                (status, body) -> status == FORBIDDEN_403 && body.contains("rateLimitExceeded") ? Optional.of("rateLimitExceeded") : Optional.empty());

        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .havingCause()
                          .isInstanceOfSatisfying(HttpResponseException.class, http -> {
                              assertThat(http.statusCode()).isEqualTo(FORBIDDEN_403);
                              assertThat(http.body()).isEqualTo("rateLimitExceeded");
                              assertThat(http).hasMessageNotContaining(PERSONAL_MARKER);
                          });
    }

    /// The parser and the detail extractor are carried into a retry, which is when a flaky endpoint is being hit.
    @Test
    void callSuppressingResponseBody_withParser_keepsTheParserAndTheDetailExtractorAcrossARetry() {
        String rejectedUrl = BASE + "/rejected-after-retry";
        urlToFailure.put(rejectedUrl, new IOException("transport boom"));
        failingAttemptCountsByUrl.put(rejectedUrl, 1);
        urlToBody.put(rejectedUrl, "{\"reason\":\"forbidden\",\"message\":\"" + PERSONAL_MARKER + "\"}");
        urlToHttpStatus.put(rejectedUrl, FORBIDDEN_403);
        String acceptedUrl = BASE + "/accepted-after-retry";
        urlToFailure.put(acceptedUrl, new IOException("transport boom"));
        failingAttemptCountsByUrl.put(acceptedUrl, 1);
        urlToBody.put(acceptedUrl, "<multistatus/>");

        CompletableFuture<Integer> rejectedCall = RestClients.callSuppressingResponseBody(callFactory.apply(rejectedUrl),
                                                                                          String::length,
                                                                                          1,
                                                                                          REDACTED_URL,
                                                                                          (_, _) -> Optional.of("forbidden"));
        CompletableFuture<Integer> acceptedCall = RestClients.callSuppressingResponseBody(callFactory.apply(acceptedUrl),
                                                                                          String::length,
                                                                                          1,
                                                                                          REDACTED_URL,
                                                                                          UnsuccessfulResponseDetailExtractor.NONE);

        assertThat(rejectedCall).failsWithin(Duration.ZERO)
                                .withThrowableThat()
                                .havingCause()
                                .isInstanceOfSatisfying(HttpResponseException.class, http -> assertThat(http.body()).isEqualTo("forbidden"));
        assertThat(acceptedCall).succeedsWithin(Duration.ZERO).isEqualTo("<multistatus/>".length());
        assertThat(urlHitCount).containsEntry(rejectedUrl, 2).containsEntry(acceptedUrl, 2);
    }

    @Test
    void callSuppressingResponseBody_withUnsuccessfulResponseDetail_withholdsTheBodyWhenThereIsNoDetail() {
        urlToBody.put(BASE + "/proxy", "<html>" + PERSONAL_MARKER + "</html>");
        urlToHttpStatus.put(BASE + "/proxy", INTERNAL_SERVER_ERROR_500);

        CompletableFuture<String> future = RestClients.callSuppressingResponseBody(callFactory.apply(BASE + "/proxy"),
                                                                                   Function.identity(),
                                                                                   0,
                                                                                   REDACTED_URL,
                                                                                   (_, _) -> Optional.empty());

        assertThat(future).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .havingCause()
                          .isInstanceOfSatisfying(HttpResponseException.class, http -> {
                              assertThat(http.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
                              assertThat(http.body()).isEqualTo(RestClients.WITHHELD_BODY);
                          });
    }

    @Test
    void forEachPage_handsEveryPageToTheConsumerInOrderAndCompletesWithTheLast() {
        urlToBody.put(BASE + "/p1", """
                                    {"results": [{"name": "a"}], "next": "http://example.test/p2"}
                                    """);
        urlToBody.put(BASE + "/p2", """
                                    {"results": [{"name": "b"}], "next": null}
                                    """);
        List<String> consumedItemNames = new ArrayList<>();

        CompletableFuture<Page> lastPage = RestClients.forEachPage(url -> RestClients.call(callFactory.apply(url), Page.class),
                                                                   BASE + "/p1",
                                                                   page -> page.results().forEach(item -> consumedItemNames.add(item.name())),
                                                                   Page::nextUrl);

        assertThat(lastPage).succeedsWithin(Duration.ZERO).extracting(Page::nextUrl, OPTIONAL).isEmpty();
        assertThat(consumedItemNames).containsExactly("a", "b");
    }

    @Test
    void forEachPage_failingFetch_failsWithoutCallingTheConsumer() {
        urlToFailure.put(BASE + "/broken", new IOException("transport boom"));
        List<Page> consumedPages = new ArrayList<>();

        CompletableFuture<Page> lastPage = RestClients.forEachPage(url -> RestClients.call(callFactory.apply(url), Page.class, 0),
                                                                   BASE + "/broken",
                                                                   consumedPages::add,
                                                                   Page::nextUrl);

        assertThat(lastPage).failsWithin(Duration.ZERO).withThrowableThat().withRootCauseInstanceOf(IOException.class);
        assertThat(consumedPages).isEmpty();
    }

    @Test
    void paginate_negativeExpectedTotalCount_rejected() {
        assertThatThrownBy(() -> RestClients.paginate(callFactory, BASE + "/x", -1,
                                                      new TypeToken<>() {}, Page::results, Page::nextUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedTotalCount");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Page(List<Item> results, Optional<String> nextUrl) {
        @JsonCreator
        Page(@JsonProperty("results") List<Item> results,
             @JsonProperty("next") Optional<String> nextUrl) {
            this.results = results;
            this.nextUrl = nextUrl;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String name) {
        @JsonCreator
        Item(@JsonProperty("name") String name) {
            this.name = name;
        }
    }
}
