package net.yudichev.jiotty.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseOauthAccessTokenResponse {
    @JsonProperty("access_token")
    String accessToken();

    @JsonProperty("refresh_token")
    String refreshToken();

    @JsonProperty("expires_in")
    int expiresInSec();
}
