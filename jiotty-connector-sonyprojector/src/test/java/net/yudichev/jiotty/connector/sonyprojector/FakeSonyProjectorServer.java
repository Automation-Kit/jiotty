package net.yudichev.jiotty.connector.sonyprojector;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MoreThrowables;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class FakeSonyProjectorServer implements Closeable {
    private static final Charset CHARSET = StandardCharsets.US_ASCII;
    private static final String LINE_TERMINATOR = "\r\n";
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private final ServerSocket serverSocket;
    private final int port;
    private final Thread thread;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private FakeSonyProjectorServer(ConnectionHandler handler) {
        serverSocket = MoreThrowables.getAsUnchecked(() -> new ServerSocket(0));
        port = serverSocket.getLocalPort();
        thread = new Thread(() -> run(handler), "sony-projector-test-server");
        thread.setDaemon(true);
        thread.start();
    }

    static FakeSonyProjectorServer start(ConnectionHandler handler) {
        return new FakeSonyProjectorServer(handler);
    }

    int port() {
        return port;
    }

    void awaitCompletion(Duration timeout) {
        MoreThrowables.asUnchecked(() -> completion.get(timeout.toMillis(), MILLISECONDS));
    }

    @Override
    public void close() {
        Closeable.closeIfNotNull(serverSocket);
        MoreThrowables.asUnchecked(() -> thread.join(DEFAULT_JOIN_TIMEOUT.toMillis()));
    }

    private void run(ConnectionHandler handler) {
        try (serverSocket; var socket = serverSocket.accept()) {
            socket.setSoTimeout((int) DEFAULT_READ_TIMEOUT.toMillis());
            try (var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), CHARSET));
                 var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), CHARSET))) {
                handler.handle(reader, writer);
            }
            completion.complete(null);
        } catch (Exception e) {
            completion.completeExceptionally(e);
        }
    }

    static void writeLine(BufferedWriter writer, String line) {
        MoreThrowables.asUnchecked(() -> {
            writer.write(line);
            writer.write(LINE_TERMINATOR);
            writer.flush();
        });
    }

    static String readLine(BufferedReader reader) {
        return MoreThrowables.getAsUnchecked(() -> {
            String line = reader.readLine();
            checkState(line != null, "Unexpected end of stream");
            return line;
        });
    }

    interface ConnectionHandler {
        void handle(BufferedReader reader, BufferedWriter writer) throws Exception;
    }
}
