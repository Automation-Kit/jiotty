package net.yudichev.jiotty.common.security;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

/// Represents a state of authentication.
public sealed interface AuthState {

    record Success(String authInfo) implements AuthState, StringFormattable {
        @Override
        public String toString() {
            return toString(16);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "Success[");
            LogRedaction.appendRedacted(appendable, authInfo);
            Append.to(appendable, ']');
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
