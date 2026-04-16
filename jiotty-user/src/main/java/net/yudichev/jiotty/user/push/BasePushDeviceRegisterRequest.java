package net.yudichev.jiotty.user.push;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.util.Optional;

/// Wire shape of `POST /api/push/devices`. Deserialised directly from the JSON body.
@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
public interface BasePushDeviceRegisterRequest {
    String deviceId();

    String token();

    Optional<String> platform();

    Optional<String> appVersion();
}
