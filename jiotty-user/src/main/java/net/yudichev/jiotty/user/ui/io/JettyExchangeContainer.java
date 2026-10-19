package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import net.yudichev.jiotty.common.lang.Closeable;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.thread.Scheduler;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/// An [ExchangeContainer] over a Jetty request. Its completion is the core request's, which Jetty reaches only once every write to the response has
/// completed or been cancelled — later than the servlet-level [AsyncListener#onComplete(AsyncEvent)], which a write still in flight can outlive.
final class JettyExchangeContainer implements ExchangeContainer {
    private final ServletContextRequest request;

    JettyExchangeContainer(ServletContextRequest request) {
        this.request = checkNotNull(request, "request");
    }

    @Override
    public void addCompletionListener(Runnable onCompleted) {
        checkNotNull(onCompleted, "onCompleted");
        Request.addCompletionListener(request, _ -> onCompleted.run());
    }

    @Override
    public Closeable schedule(Duration delay, Runnable task) {
        // The server's own timer: a single thread that also drives every connection's idle timeout, so `task` must hand its work on.
        Scheduler.Task scheduled = request.getComponents().getScheduler().schedule(task, delay.toNanos(), NANOSECONDS);
        return scheduled::cancel;
    }

    @Override
    public void abort(Throwable cause) {
        request.getServletChannel().abort(checkNotNull(cause, "cause"));
    }
}
