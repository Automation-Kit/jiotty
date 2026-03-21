/*
Copyright (C) 2016, 2017 Bengt Martensson.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or (at
your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see http://www.gnu.org/licenses/.
*/

package net.yudichev.jiotty.connector.ir.lirc;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.PackagePrivateImmutablesStyle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Collections.emptyList;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.forCloseables;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.immutables.value.Value.Immutable;
import static org.immutables.value.Value.Parameter;

/// Abstract class for a <a href="http://www.lirc.org">LIRC</a> client. Functionally, it resembles the command line program irsend.
abstract class BaseLircClient extends BaseLifecycleComponent implements LircClient {
    public static final Charset CHARSET = StandardCharsets.US_ASCII;
    private static final Pattern COMMAND_LIST_CLEANUP_PATTERN = Pattern.compile("\\S*\\s+");

    protected final Logger logger = LogManager.getLogger(getClass());
    private final ExecutorFactory executorFactory;
    private final RetryableOperationExecutor heartbeatRetryableOperationExecutor;
    private final RetryableOperationExecutor commandRetryableOperationExecutor;

    private Streams streams;
    private SchedulingExecutor executor;

    protected BaseLircClient(ExecutorFactory executorFactory,
                             RetryableOperationExecutor heartbeatRetryableOperationExecutor,
                             RetryableOperationExecutor commandRetryableOperationExecutor) {
        this.executorFactory = checkNotNull(executorFactory);
        this.heartbeatRetryableOperationExecutor = checkNotNull(heartbeatRetryableOperationExecutor);
        this.commandRetryableOperationExecutor = checkNotNull(commandRetryableOperationExecutor);
    }

    @Override
    protected final void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("lirc-client");
        streams = connect();
        scheduleNextHeartbeat();
    }

    private void scheduleNextHeartbeat() {
        // serves as a heartbeat to detect connection problems
        executor.schedule(Duration.ofSeconds(30), this::heartbeat);
    }

    private void heartbeat() {
        heartbeatRetryableOperationExecutor.withBackOffAndRetry("heartbeat: getVersion",
                                                                () -> getVersion()
                                                                        .exceptionally(e -> {
                                                                            logger.info("heartbeat processing failed", e);
                                                                            closeSafelyIfNotNull(logger, streams);
                                                                            streams = connect();
                                                                            //noinspection ReturnOfNull
                                                                            return null;
                                                                        })
                                                                        .thenRun(this::scheduleNextHeartbeat))
                                           .exceptionally(e -> {
                                               logger.error("Heartbeat failed", e);
                                               return null;
                                           });
    }

    protected abstract Streams connect();

    @SuppressWarnings("AssignmentToNull") // convention
    @Override
    protected void doStop() {
        executor.execute(() -> {
            closeSafelyIfNotNull(logger, streams);
            streams = null;
        });
        closeSafelyIfNotNull(logger, executor);
        executor = null;
    }

    @Override
    public CompletableFuture<Void> sendIrCommand(String remote, String command, int count) {
        return sendCommand("SEND_ONCE " + remote + " " + command + " " + (count - 1)).thenApply(strings -> null);
    }

    @Override
    public CompletableFuture<Void> sendIrCommandRepeat(String remote, String command) {
        return sendCommand("SEND_START " + remote + " " + command).thenApply(strings -> null);
    }

    @Override
    public CompletableFuture<Void> stopIr(String remote, String command) {
        return sendCommand("SEND_STOP " + remote + " " + command).thenApply(strings -> null);
    }

    @Override
    public CompletableFuture<List<String>> getRemotes() {
        return sendCommand("LIST");
    }

    @Override
    public CompletableFuture<List<String>> getCommands(String remote) {
        return sendCommand("LIST " + checkNotNull(remote))
                .thenApply(commands -> commands.stream()
                                               .map(s -> COMMAND_LIST_CLEANUP_PATTERN.matcher(s).replaceAll(""))
                                               .collect(toImmutableList()));
    }

    @Override
    public CompletableFuture<Void> setTransmitters(Iterable<Integer> transmitters) {
        long mask = 0L;
        for (int transmitter : transmitters) {
            mask |= (1L << (transmitter - 1));
        }

        return setTransmitters(mask);
    }

    @Override
    public CompletableFuture<Void> setTransmitters(long mask) {
        return sendCommand("SET_TRANSMITTERS " + mask).thenApply(strings -> null);
    }

    @Override
    public CompletableFuture<String> getVersion() {
        return sendCommand("VERSION")
                .thenApply(result -> {
                    if (result.isEmpty()) {
                        throw new LircServerException("VERSION command returned no lines");
                    }
                    return result.getFirst();
                });
    }

    @Override
    public CompletableFuture<Void> setInputLog() {
        return setInputLog("null");
    }

    @Override
    public CompletableFuture<Void> setInputLog(String logPath) {
        return sendCommand("SET_INPUTLOG " + checkNotNull(logPath)).thenApply(strings -> null);
    }

    @Override
    public CompletableFuture<Void> setDriverOption(String key, String value) {
        return sendCommand("DRV_OPTION " + key + " " + value).thenApply(strings -> null);
    }

    @Override
    public CompletableFuture<Void> simulate(String eventString) {
        return sendCommand("SIMULATE " + eventString).thenApply(strings -> null);
    }

    protected abstract String connectionName();

    @SuppressWarnings({"OverlyLongMethod", "NestedSwitchStatement"}) // reads OK
    private CompletableFuture<List<String>> sendCommand(String command) {
        checkStarted();
        return commandRetryableOperationExecutor.withBackOffAndRetry("lirc: " + command, () -> supplyAsync(() -> {
            logger.debug("Sending command `{}' to Lirc@{}", command, connectionName());
            streams.write(command + '\n');

            ImmutableList.Builder<String> resultBuilder = null;
            State state = State.BEGIN;
            int linesReceived = 0;
            int linesExpected = -1;

            while (state != State.DONE) {
                String line = streams.read();
                logger.debug("Received \"{}\"", line);

                if (line == null) {
                    state = State.DONE;
                    continue;
                }
                switch (state) {
                    case BEGIN -> {
                        if ("BEGIN".equals(line)) {
                            state = State.MESSAGE;
                        }
                    }
                    case MESSAGE -> state = line.trim().equalsIgnoreCase(command) ? State.STATUS : State.BEGIN;
                    case STATUS -> state = switch (line) {
                        case "SUCCESS" -> State.DATA;
                        case "END" -> State.DONE;
                        case "ERROR" -> throw new LircServerException("command failed: " + command);
                        default -> throw new BadPacketException("unknown response: " + command);
                    };
                    case DATA -> state = switch (line) {
                        case "END" -> State.DONE;
                        case "DATA" -> State.N;
                        default -> throw new BadPacketException("unknown response: " + command);
                    };
                    case N -> {
                        try {
                            linesExpected = Integer.parseInt(line);
                        } catch (NumberFormatException ignored) {
                            throw new BadPacketException("integer expected; got: " + command);
                        }
                        state = linesExpected == 0 ? State.END : State.DATA_N;
                    }
                    case DATA_N -> {
                        if (resultBuilder == null) {
                            resultBuilder = ImmutableList.builderWithExpectedSize(8);
                        }
                        resultBuilder.add(line);
                        linesReceived++;
                        if (linesReceived == linesExpected) {
                            state = State.END;
                        }
                    }
                    case END -> {
                        if ("END".equals(line)) {
                            state = State.DONE;
                        } else {
                            throw new BadPacketException("\"END\" expected but \"" + line + "\" received");
                        }
                    }
                    case DONE -> {
                        // success, nothing to do
                    }
                    default -> throw new RuntimeException("Unhandled case (programming error)");
                }
            }
            logger.debug("Lirc command succeeded.");
            return resultBuilder == null ? emptyList() : resultBuilder.build();
        }, executor));
    }

    private enum State {
        BEGIN,
        MESSAGE,
        STATUS,
        DATA,
        N,
        DATA_N,
        END,
        DONE // new
    }

    @Immutable
    @PackagePrivateImmutablesStyle
    abstract static class BaseStreams implements Closeable {
        @Parameter
        public abstract BufferedReader in();

        @Parameter
        public abstract BufferedWriter out();

        void write(String cmd) {
            asUnchecked(() -> {
                out().write(cmd);
                out().flush(); // just to be safe
            });
        }

        String read() {
            return getAsUnchecked(in()::readLine);
        }

        @Override
        public final void close() {
            forCloseables(out(), in()).close();
        }
    }

    private static class BadPacketException extends RuntimeException {
        BadPacketException(String message) {
            super(message);
        }
    }
}
