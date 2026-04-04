package net.yudichev.jiotty.security;

/// Represents a state of authentication.
public sealed interface AuthState {

    record Success(String authInfo) implements AuthState {}

    sealed interface Failure extends AuthState {
        String description();
    }

    /// The refresh token is no longer valid; the user must re-authorise.
    record PermanentFailure(String description) implements Failure {}

    /// A transient error occurred; the system may recover automatically.
    record TransientFailure(String description) implements Failure {}
}
