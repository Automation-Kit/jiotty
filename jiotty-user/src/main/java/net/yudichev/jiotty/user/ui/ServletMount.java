package net.yudichev.jiotty.user.ui;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.server.Handler;

/// Pluggable Jetty handler contributed by external modules into the [UIHttpServer]'s handler sequence.
///
/// Implementations typically build their own [ServletContextHandler] with a distinct context path so requests are routed to the right backend by Jetty's
/// standard context-matching.
public interface ServletMount {
    /// Builds a Jetty handler. Called once during the host server's start phase.
    Handler buildHandler();
}
