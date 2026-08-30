package net.yudichev.jiotty.common.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.misc.SharedUpstreamOutage;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class RestClientsTest {

    private static final String BASE = "http://example.test";
    private final Map<String, String> urlToBody = new HashMap<>();
    /// Overrides the default HTTP 200 for a given URL. Tests asserting non-2xx behaviour put the status code here and the error payload in [#urlToBody] under
    /// the same URL key; the [#setUp] dispatcher composes both into a non-2xx [Response].
    private final Map<String, Integer> urlToHttpStatus = new HashMap<>();
    private final Map<String, Integer> urlHitCount = new HashMap<>();
    /// URLs that should be answered with an IOException instead of a body.
    private final Map<String, IOException> urlToFailure = new HashMap<>();
    private OkHttpClient httpClient;
    private Function<String, Call> callFactory;

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
                urlHitCount.merge(url, 1, Integer::sum);
                IOException failure = urlToFailure.get(url);
                if (failure != null) {
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
