package net.yudichev.jiotty.common.rest;

import java.util.Optional;

/// Reads the token an `Authorization: Bearer` header carries.
public final class BearerTokens {
    /// The scheme that opens a bearer `Authorization` header value, with the space that follows it.
    public static final String BEARER_PREFIX = "Bearer ";

    private BearerTokens() {
    }

    /// Returns the token `authorizationHeader` carries, trimmed.
    ///
    /// @param authorizationHeader the request's `Authorization` header; empty for a request without one
    /// @return empty when the header is absent, names another scheme, or carries a blank token
    public static Optional<String> fromAuthorizationHeader(Optional<String> authorizationHeader) {
        if (authorizationHeader.isEmpty() || !authorizationHeader.get().startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = authorizationHeader.get().substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
