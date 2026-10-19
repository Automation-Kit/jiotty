package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletRequest;
import net.yudichev.jiotty.common.lang.Closeable;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;

import static com.google.common.base.Preconditions.checkNotNull;

/// The container's services one exchange takes beyond its [AsyncContext]: a callback once the container has finished with the exchange, a timer, and an
/// abort. An [AsyncContext] that implements this interface itself provides them.
public interface ExchangeContainer {
    /// Registers `onCompleted`, invoked once when the container has finished with the exchange and holds none of the buffers written to its response. Must
    /// be called on the thread dispatching the request.
    void addCompletionListener(Runnable onCompleted);

    /// Runs `task` on the container's timer thread after `delay`.
    ///
    /// @return cancels the task, if it has not run
    /// @throws RejectedExecutionException if the timer has stopped, as when the server is stopping
    Closeable schedule(Duration delay, Runnable task);

    /// Ends the exchange without completing its response, so the client sees the response cut off. Safe from any thread.
    ///
    /// @param cause why the exchange is aborted
    void abort(Throwable cause);

    /// Returns the container serving `request`, whose async cycle is `asyncContext`.
    static ExchangeContainer of(ServletRequest request, AsyncContext asyncContext) {
        if (asyncContext instanceof ExchangeContainer container) {
            return container;
        }
        return new JettyExchangeContainer(checkNotNull(ServletContextRequest.getServletContextRequest(request), "a Jetty request is required"));
    }
}
