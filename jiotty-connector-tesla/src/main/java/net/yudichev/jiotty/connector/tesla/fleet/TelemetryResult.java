package net.yudichev.jiotty.connector.tesla.fleet;

import org.jspecify.annotations.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/// The outcome of decoding a single telemetry message delivered to a [TeslaTelemetry] subscriber: either the decoded payload ([Success]) or a description of
/// why decoding failed ([Error]).
///
/// @param <T> the decoded payload type
@SuppressWarnings("unused")
public sealed interface TelemetryResult<T> permits TelemetryResult.Success, TelemetryResult.Error {
    /// A message decoded successfully.
    ///
    /// @param value the decoded payload
    record Success<T>(T value) implements TelemetryResult<T> {
        public Success {
            checkNotNull(value);
        }
    }

    /// A message could not be decoded. `message` is VIN-free (any vehicle reference is reduced to a non-reversible redacted form) and carries no raw payload,
    /// so it is safe to log or include in an alert.
    ///
    /// @param message a human-readable, VIN-free, payload-free description of the failure
    /// @param cause   the exception that caused the failure, or `null` for a structural failure with no exception (e.g. a malformed topic)
    record Error<T>(String message, @Nullable Throwable cause) implements TelemetryResult<T> {
        public Error {
            checkNotNull(message);
        }
    }
}
