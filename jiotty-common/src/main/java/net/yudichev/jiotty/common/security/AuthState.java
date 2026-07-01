package net.yudichev.jiotty.common.security;

/// Represents a state of authentication.
public sealed interface AuthState {

    record Success(String authInfo) implements AuthState {
        @Override
        public String toString() {
            var sb = new StringBuilder(16).append("Success[");
            LogRedaction.appendRedacted(sb, authInfo);
            return sb.append(']').toString();
        }
    }

    sealed interface Failure extends AuthState {
        String description();
    }

    /// The refresh token is no longer valid; the user must re-authorise.
    record PermanentFailure(String description) implements Failure {}

    /// A transient error occurred; the system may recover automatically.
    record TransientFailure(String description) implements Failure {}
}
