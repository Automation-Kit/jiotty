package net.yudichev.jiotty.common.rest;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/// Stubs a mock [OkHttpClient] so each [OkHttpClient#newCall(Request)] returns a [Call] that, on `enqueue`, delivers the response the test's responder
/// produces for the request — or stays pending when the responder returns `null`, for tests that only care about the request being made.
public final class OkHttpStubs {
    private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");

    private OkHttpStubs() {
    }

    /// @param responder maps each issued request to the response to deliver, or to `null` to leave that call pending forever
    /// @return every request issued so far, in order
    public static List<Request> stubCalls(OkHttpClient httpClient, Function<Request, @Nullable Response> responder) {
        List<Request> issuedRequests = new ArrayList<>();
        stubNewCall(httpClient, (call, request, callback) -> {
            issuedRequests.add(request);
            Response response = responder.apply(request);
            if (response != null) {
                callback.onResponse(call, response);
            }
        });
        return issuedRequests;
    }

    /// Stubs a mock [OkHttpClient] so each enqueued call waits in the returned list, in the order it was enqueued, until the test responds to or fails it.
    ///
    /// @return the calls still pending, which the test removes as it completes them
    public static List<PendingCall> stubDeferredCalls(OkHttpClient httpClient) {
        List<PendingCall> pendingCalls = new ArrayList<>();
        stubNewCall(httpClient, (call, request, callback) -> pendingCalls.add(new PendingCall(call, request, callback)));
        return pendingCalls;
    }

    private static void stubNewCall(OkHttpClient httpClient, EnqueueHandler enqueueHandler) {
        // Real ones, so the client shuts down as a real client does.
        lenient().doReturn(new Dispatcher()).when(httpClient).dispatcher();
        lenient().doReturn(new ConnectionPool()).when(httpClient).connectionPool();
        // The doAnswer form lets a test re-stub newCall without running the previous stub's answer.
        lenient().doAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            Call call = mock(Call.class);
            lenient().when(call.request()).thenReturn(request);
            lenient().doAnswer(enqueueInvocation -> {
                enqueueHandler.onEnqueue(call, request, enqueueInvocation.getArgument(0));
                return null;
            }).when(call).enqueue(any());
            return call;
        }).when(httpClient).newCall(any());
    }

    /// Removes and returns the one call waiting in `pendingCalls`, failing the test when there is not exactly one.
    public static PendingCall takeOnlyPendingCall(List<PendingCall> pendingCalls) {
        assertThat(pendingCalls).as("exactly one call should be in flight").hasSize(1);
        return pendingCalls.removeFirst();
    }

    /// The body `request` carries, decoded as UTF-8.
    public static String bodyOf(Request request) {
        try (var buffer = new Buffer()) {
            asUnchecked(() -> checkNotNull(request.body()).writeTo(buffer));
            return buffer.readUtf8();
        }
    }

    /// A JSON response to `request` with the given status and body.
    public static Response response(Request request, int status, String json) {
        return response(request, status, APPLICATION_JSON, json);
    }

    public static Response response(Request request, int status, MediaType contentType, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status == OK_200 ? "OK" : "Error")
                .body(ResponseBody.create(body, contentType))
                .build();
    }

    private interface EnqueueHandler {
        void onEnqueue(Call call, Request request, Callback callback) throws IOException;
    }

    /// A call enqueued on a client stubbed by [#stubDeferredCalls], waiting for the test to respond to or fail it.
    public record PendingCall(Call call, Request request, Callback callback) {
        public PendingCall {
            checkNotNull(call);
            checkNotNull(request);
            checkNotNull(callback);
        }

        /// Answers with a JSON response.
        public void respond(int status, String json) {
            respond(response(request, status, json));
        }

        public void respond(Response response) {
            asUnchecked(() -> callback.onResponse(call, response));
        }

        public void fail(IOException e) {
            callback.onFailure(call, e);
        }
    }
}
