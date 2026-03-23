package net.yudichev.jiotty.security;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import java.time.Instant;

@Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonDeserialize
interface BaseOauthAccessToken {
    @Value.Parameter
    String accessToken();

    @Value.Parameter
    String refreshToken();

    @Value.Parameter
    Instant expiryTime();
}
