package net.yudichev.jiotty.connector.expopush;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/// A single message to be delivered to one Expo push token. See [Expo
/// docs](https://docs.expo.dev/push-notifications/sending-notifications/#message-request-format).
@Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_ABSENT)
public interface BaseExpoPushMessage {
    /// Target Expo push token, e.g. `ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]`.
    @JsonProperty("to")
    String token();

    @JsonProperty("title")
    String title();

    @JsonProperty("body")
    String body();

    /// Opaque payload delivered to the client's notification response listener — used for deep-linking.
    @JsonProperty("data")
    Map<String, String> data();

    /// Android notification channel id. Defaults to the app default channel if not set.
    @JsonProperty("channelId")
    Optional<String> channelId();

    /// iOS-only. `"default"` plays the system sound; a filename such as `"my_sound.wav"` plays a custom sound previously bundled into the app via the
    /// `expo-notifications` config plugin. Omit for a silent delivery. No effect on Android — Android sound is a property of the notification channel
    /// referenced by [#channelId()].
    @JsonProperty("sound")
    Optional<String> sound();

    /// Seconds FCM/APNs may keep the message queued for a device that is not immediately reachable, delivering it when the device reconnects. Expo treats
    /// an omitted value as 0: the message is discarded unless the device is reachable at send time.
    @JsonProperty("ttl")
    OptionalInt ttlSeconds();
}
