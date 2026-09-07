package net.yudichev.jiotty.connector.ir.lirc;

import java.net.SocketException;
import java.util.function.Predicate;

import static com.google.common.base.Throwables.getCausalChain;

/// Retries a dropped socket and a command the LIRC server refused, both of which the next attempt can get through. The chain is walked because the handler
/// applies this to the failure as thrown, and a socket drop reaches it wrapped.
final class LircRetryableExceptionPredicate implements Predicate<Throwable> {
    @Override
    public boolean test(Throwable input) {
        for (Throwable cause : getCausalChain(input)) {
            if (cause instanceof SocketException
                // LIRC server failed to execute command; seen this happen: [1296690.602765] ir_toy 3-2:1.1: failed to send tx start command: -16
                || cause instanceof LircServerException) {
                return true;
            }
        }
        return false;
    }
}
