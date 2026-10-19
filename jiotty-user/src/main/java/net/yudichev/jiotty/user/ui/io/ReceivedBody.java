package net.yudichev.jiotty.user.ui.io;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;

/// The outcome of reading a request body with [RequestBodies#read].
///
/// @param <T> the type the body parses to
@SuppressWarnings("unused") // `T` is named by no member here, but it types every case, so a `switch` over a body binds the parsed value without a cast
public sealed interface ReceivedBody<T> {
    /// @param value the parsed body
    record Parsed<T>(T value) implements ReceivedBody<T> {
        public Parsed {
            checkNotNull(value, "value");
        }
    }

    /// The request carried no body.
    final class Empty<T> implements ReceivedBody<T> {
        private static final Empty<?> INSTANCE = new Empty<>();

        private Empty() {
        }

        @SuppressWarnings("unchecked") // the one instance carries no value of the type it is cast to
        public static <T> Empty<T> instance() {
            return (Empty<T>) INSTANCE;
        }
    }

    /// The body is longer than the limit it was read with; the rest of it is left unread.
    final class TooLarge<T> implements ReceivedBody<T> {
        private static final TooLarge<?> INSTANCE = new TooLarge<>();

        private TooLarge() {
        }

        @SuppressWarnings("unchecked") // the one instance carries no value of the type it is cast to
        public static <T> TooLarge<T> instance() {
            return (TooLarge<T>) INSTANCE;
        }
    }

    /// @param cause why the body does not parse
    record Malformed<T>(IOException cause) implements ReceivedBody<T> {
        public Malformed {
            checkNotNull(cause, "cause");
        }
    }
}
