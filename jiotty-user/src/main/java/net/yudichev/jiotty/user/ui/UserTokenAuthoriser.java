package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.persistence.UserProfile;

import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

/// Authenticates bearer tokens for UI requests and publishes the current token state to handlers registered via [#subscribeToTokenState(String, Consumer)].
///
/// Subscriptions are token-scoped. If a state is already known for `token`, the handler may be called synchronously from
/// [#subscribeToTokenState(String,Consumer)]. If the token is still being resolved, the handler is called later when a final state becomes available.
public interface UserTokenAuthoriser {
    void deliverTokenStateTo(String token, Consumer<? super TokenState> handler);

    Closeable subscribeToTokenState(String token, Consumer<? super TokenState> handler);

    sealed interface TokenState permits TokenAuthenticated, TokenNotAuthenticated {}

    record TokenAuthenticated(UserProfile profile, UIServer uiServer) implements TokenState {
        public TokenAuthenticated {
            checkNotNull(profile, "profile");
        }
    }

    record TokenNotAuthenticated(Reason reason, String technicalDescription) implements TokenState {
        public TokenNotAuthenticated {
            checkNotNull(reason, "reason");
            checkNotNull(technicalDescription, "technicalDescription");
        }

        public enum Reason {
            INVALID,
            USER_DISABLED,
            TECHNICAL_FAILURE,
        }
    }
}
