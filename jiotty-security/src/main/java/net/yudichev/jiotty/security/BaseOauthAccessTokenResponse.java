package net.yudichev.jiotty.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.util.Optional;

@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseOauthAccessTokenResponse {
    @JsonProperty("access_token")
    String accessToken();

    /// Per RFC 6749 Section 6, the server may omit `refresh_token` in a token refresh response; the client should keep using the existing refresh token in that
    /// case.
    @JsonProperty("refresh_token")
    Optional<String> refreshToken();

    @JsonProperty("expires_in")
    int expiresInSec();

    /// Per RFC 6749 Section 5.1, `token_type` is required. Validated to be `Bearer` (case-insensitive, per RFC 6750).
    @JsonProperty("token_type")
    Optional<String> tokenType();
}
