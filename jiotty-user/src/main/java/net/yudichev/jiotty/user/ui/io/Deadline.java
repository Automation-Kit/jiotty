package net.yudichev.jiotty.user.ui.io;

import java.time.Duration;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// How long an [AsyncExchange] may go unanswered, and the answer it then gets. A deadline bounds the time until something takes the exchange's
/// [AsyncExchange.Claim]; the connector's idle timeout bounds a slow transfer after that.
public sealed interface Deadline {
    /// Returns the deadline of an exchange that may stay unanswered indefinitely.
    static Deadline none() {
        return None.INSTANCE;
    }

    /// @param timeout           how long the exchange may go unanswered; positive
    /// @param answer            the answer once it has; it must not depend on the request, being built before the deadline runs
    /// @param onEncodingFailure invoked on a container thread with the failure when `answer`'s body cannot be encoded
    /// @param onExpired         invoked on a container thread, before `answer` is written, with what the exchange was waiting on
    static Deadline after(Duration timeout, Answer answer, Consumer<? super Exception> onEncodingFailure, Consumer<Stall> onExpired) {
        return new After(timeout, answer, onEncodingFailure, onExpired);
    }

    /// What an exchange was waiting on when its deadline expired.
    enum Stall {
        /// Its request body was still arriving: the client's own slowness, which any caller can cause.
        RECEIVING_BODY(false),
        /// Its body had arrived, or it has none, and the server had not answered.
        HANDLING(true);

        private final boolean serverStalled;

        Stall(boolean serverStalled) {
            this.serverStalled = serverStalled;
        }

        /// Whether the server kept the exchange waiting, which is worth an alert; the client keeping it waiting is routine.
        public boolean serverStalled() {
            return serverStalled;
        }
    }

    enum None implements Deadline {
        INSTANCE
    }

    record After(Duration timeout, Answer answer, Consumer<? super Exception> onEncodingFailure, Consumer<Stall> onExpired) implements Deadline {
        public After {
            checkArgument(timeout.isPositive(), "timeout must be positive: %s", timeout);
            checkNotNull(answer, "answer");
            checkNotNull(onEncodingFailure, "onEncodingFailure");
            checkNotNull(onExpired, "onExpired");
        }
    }
}
