package net.yudichev.jiotty.user.ui.io.testing;

import com.google.common.collect.ImmutableList;
import jakarta.servlet.http.HttpServletRequest;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

/// A test's request as one dispatch sees it, recording every call made on it once that dispatch has returned.
///
/// After the dispatch, the container may recycle the request the moment the exchange completes, so only the holder of the exchange's claim may touch it, and
/// only through [AsyncExchange.Claim#request()], which reaches the stubbed request the [FakeAsyncContext] was given, not the recording view [#dispatch]
/// passes to the handler.
public final class DispatchedRequest {
    private final HttpServletRequest recordingView;
    /// Appended by whichever thread reads the request late, and read by the test.
    private final List<String> callsAfterDispatch = new CopyOnWriteArrayList<>();
    /// Set by the test's thread once the dispatch returns, and read by any thread that calls the request.
    private volatile boolean dispatchReturned;

    private DispatchedRequest(HttpServletRequest request) {
        checkNotNull(request, "request");
        recordingView = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                                                                    new Class<?>[]{HttpServletRequest.class},
                                                                    (_, method, args) -> {
                                                                        if (dispatchReturned) {
                                                                            callsAfterDispatch.add(method.getName());
                                                                        }
                                                                        try {
                                                                            return method.invoke(request, args);
                                                                        } catch (InvocationTargetException e) {
                                                                            throw e.getCause();
                                                                        }
                                                                    });
    }

    /// Runs `handlerDispatch` with a view of `request` that records the calls made on it after `handlerDispatch` returns. The tasks the dispatch starts on
    /// `asyncContext` run once it returns, as a container's pool runs them on its own threads.
    ///
    /// @param request         the request the test stubs, which `asyncContext` must have been given
    /// @param asyncContext    the container serving `request`
    /// @param handlerDispatch the handler's dispatch, given the recording view
    public static DispatchedRequest dispatch(HttpServletRequest request, FakeAsyncContext asyncContext, Consumer<HttpServletRequest> handlerDispatch) {
        var recorder = new DispatchedRequest(request);
        asyncContext.holdStartedTasks();
        try {
            handlerDispatch.accept(recorder.recordingView);
        } finally {
            recorder.dispatchReturned = true;
            asyncContext.runHeldTasks();
        }
        return recorder;
    }

    /// Returns the names of the request's methods called after the dispatch returned, in the order called.
    public List<String> callsAfterDispatch() {
        return ImmutableList.copyOf(callsAfterDispatch);
    }
}
