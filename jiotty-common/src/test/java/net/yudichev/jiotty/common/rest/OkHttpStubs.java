package net.yudichev.jiotty.common.rest;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
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
    public static void stubCalls(OkHttpClient httpClient, Function<Request, @Nullable Response> responder) {
        lenient().when(httpClient.newCall(any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            Call callMock = mock(Call.class);
            lenient().when(callMock.request()).thenReturn(request);
            lenient().doAnswer(enqueueInvocation -> {
                Response response = responder.apply(request);
                if (response != null) {
                    Callback callback = enqueueInvocation.getArgument(0);
                    callback.onResponse(callMock, response);
                }
                return null;
            }).when(callMock).enqueue(any());
            return callMock;
        });
    }

    /// A JSON response to `request` with the given status and body.
    public static Response response(Request request, int status, String json) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status == OK_200 ? "OK" : "Error")
                .body(ResponseBody.create(json, APPLICATION_JSON))
                .build();
    }
}
