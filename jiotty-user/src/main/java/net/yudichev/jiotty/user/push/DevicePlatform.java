package net.yudichev.jiotty.user.push;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.Ascii;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/// The platform on which a push device runs.
///
/// Its JSON form is the constant's name in lower case: `ios`, `android` or `unknown`. Reading accepts that name in any letter case, and reads any other
/// value as [#UNKNOWN], including JSON `null` and a value that is not a string.
@JsonDeserialize(using = DevicePlatform.LenientDeserialiser.class)
public enum DevicePlatform {
    IOS("ios"),
    ANDROID("android"),
    /// A platform that the device did not report, or reported as a value that names neither [#IOS] nor [#ANDROID].
    UNKNOWN("unknown");

    private static final Map<String, DevicePlatform> PLATFORMS_BY_WIRE_VALUE = Maps.uniqueIndex(Arrays.asList(values()), DevicePlatform::wireValue);

    private final String wireValue;

    DevicePlatform(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    private String wireValue() {
        return wireValue;
    }

    /// Reads a [DevicePlatform] from JSON as the type's doc describes.
    static final class LenientDeserialiser extends JsonDeserializer<DevicePlatform> {
        @Override
        public DevicePlatform deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                return PLATFORMS_BY_WIRE_VALUE.getOrDefault(Ascii.toLowerCase(parser.getText()), UNKNOWN);
            }
            parser.skipChildren();
            return UNKNOWN;
        }

        @Override
        public DevicePlatform getNullValue(DeserializationContext context) {
            return UNKNOWN;
        }
    }
}
