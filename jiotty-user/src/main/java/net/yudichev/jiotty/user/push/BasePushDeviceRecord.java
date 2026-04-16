package net.yudichev.jiotty.user.push;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.time.Instant;
import java.util.Optional;

/// A single push-capable device registered to a user. Keyed by [#deviceId] — re-registration with the same `deviceId` replaces the existing record, so tokens
/// rotate cleanly across reinstalls and permission resets.
@Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonDeserialize
public interface BasePushDeviceRecord {
    /// Stable per-install UUID chosen by the client and persisted in its secure storage.
    String deviceId();

    /// Current Expo push token (`ExponentPushToken[...]`).
    String token();

    /// `ios` / `android` label for diagnostics; optional because pre-registration flows may not know it.
    Optional<String> platform();

    /// App version string for diagnostics.
    Optional<String> appVersion();

    Instant registeredAt();
}
