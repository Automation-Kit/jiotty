package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.persistence.UserProfile;

import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

/// Authenticates bearer tokens for UI requests and publishes the current token state to handlers registered via [#subscribeToTokenState(String, Consumer)].
///
/// Subscriptions are token-scoped. If a state is already known for `token`, the handler may be called synchronously from
/// [#subscribeToTokenState(String,Consumer)]. If the token is still being resolved, the handler is called later when a final state becomes available.
public interface UserTokenAuthoriser {
    /// Request attribute carrying [TokenAuthenticated#customData] (when present) to request handlers; the request authoriser sets it on each authenticated
    /// request before dispatch.
    String CUSTOM_DATA_REQUEST_ATTRIBUTE = "net.yudichev.jiotty.user.ui.tokenCustomData";

    void deliverTokenStateTo(String token, Consumer<? super TokenState> handler);

    Closeable subscribeToTokenState(String token, Consumer<? super TokenState> handler);

    sealed interface TokenState permits TokenAuthenticated, TokenNotAuthenticated {}

    /// @param profile         the authenticated user's profile
    /// @param uiServerRuntime the per-user UI server runtime that dispatches this token's requests
    /// @param customData      opaque, application-supplied per-token value delivered to request handlers via [#CUSTOM_DATA_REQUEST_ATTRIBUTE]; empty when the
    /// application attaches nothing
    record TokenAuthenticated(UserProfile profile, UIServerRuntime uiServerRuntime, Optional<Object> customData) implements TokenState {
        public TokenAuthenticated {
            checkNotNull(profile, "profile");
            checkNotNull(uiServerRuntime, "uiServerRuntime");
            checkNotNull(customData, "customData");
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
            /// The token itself verified, but the application refused to register a new account for it — so, unlike [#INVALID], the caller's session is
            /// still good.
            REGISTRATION_REFUSED,
            /// No account could be registered because the token's email address already belongs to a different account. Distinct from [#REGISTRATION_REFUSED]
            /// because retrying can never succeed: two provider accounts hold one address, which no client-side flow — including account linking — resolves.
            EMAIL_ALREADY_REGISTERED,
            TECHNICAL_FAILURE,
        }
    }
}
