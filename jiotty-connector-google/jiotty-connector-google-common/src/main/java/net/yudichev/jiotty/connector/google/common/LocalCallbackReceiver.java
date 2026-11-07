package net.yudichev.jiotty.connector.google.common;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.Locks.inLock;

/// Receives the OAuth authorization code on a local HTTP server, like Google's `LocalServerReceiver`, but when the redirect host is a loopback name it
/// listens on the loopback address only. `LocalServerReceiver` listens on all network interfaces whatever the host, which makes desktop firewalls (e.g.
/// Windows Defender Firewall) ask the user to let the app accept connections from the network.
final class LocalCallbackReceiver implements VerificationCodeReceiver {
    static final String CALLBACK_PATH = "/Callback";
    private static final Set<String> LOOPBACK_ADDRESS_LITERALS = Set.of("127.0.0.1", "::1", "[::1]");
    private static final String LANDING_PAGE = "<html><head><title>OAuth 2.0 Authentication Token Received</title></head>" +
                                               "<body>Received verification code. You may now close this window.</body></html>";

    private final String host;
    private final int port;
    private final CompletableFuture<String> code = new CompletableFuture<>();
    private final Lock lock = new ReentrantLock();
    private HttpServer server;

    LocalCallbackReceiver(String host, int port) {
        this.host = checkNotNull(host);
        this.port = port;
    }

    @Override
    public String getRedirectUri() throws IOException {
        lock.lock();
        try {
            checkState(server == null, "already started");
            server = HttpServer.create(bindAddress(host, port), 0);
            server.createContext(CALLBACK_PATH, this::handleCallback);
            server.start();
            return "http://" + host + ':' + server.getAddress().getPort() + CALLBACK_PATH;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String waitForCode() throws IOException {
        try {
            return code.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the authorization code", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException ioException ? ioException : new IOException(e.getCause());
        }
    }

    @Override
    public void stop() {
        inLock(lock, () -> {
            if (server != null) {
                server.stop(0);
                //noinspection AssignmentToNull marks the receiver stopped, so that stop() can be called again
                server = null;
            }
        });
    }

    InetSocketAddress boundAddress() {
        return inLock(lock, () -> {
            checkState(server != null, "not started");
            return server.getAddress();
        });
    }

    /// The loopback address for a loopback redirect host, so that only this machine can connect; otherwise all interfaces, as a browser on another
    /// machine may need to reach the callback.
    static InetSocketAddress bindAddress(String host, int port) throws UnknownHostException {
        var normalisedHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalisedHost)) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }
        if (LOOPBACK_ADDRESS_LITERALS.contains(normalisedHost)) {
            return new InetSocketAddress(InetAddress.getByName(normalisedHost.replace("[", "").replace("]", "")), port);
        }
        return new InetSocketAddress(port);
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        var parameters = queryParameters(exchange.getRequestURI().getRawQuery());
        var error = parameters.get("error");
        var receivedCode = parameters.get("code");
        try (exchange) {
            var body = LANDING_PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        if (error != null) {
            code.completeExceptionally(new IOException("Authorization failed: " + error));
        } else if (receivedCode != null) {
            code.complete(receivedCode);
        }
    }

    private static Map<String, String> queryParameters(String rawQuery) {
        var parameters = new HashMap<String, String>();
        if (rawQuery != null) {
            for (var pair : rawQuery.split("&")) {
                var separator = pair.indexOf('=');
                if (separator > 0) {
                    parameters.put(decode(pair.substring(0, separator)), decode(pair.substring(separator + 1)));
                }
            }
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
