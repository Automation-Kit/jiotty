package net.yudichev.jiotty.user.ui.io.testing;

import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.io.JsonBody;

/// A payload Jackson cannot map, being a bean with no properties, for tests of a body that cannot be encoded.
// InstantiationOfUtilityClass: the inspection takes a class with no instance members for a static holder, and an instance with none is what makes this payload
// unmappable.
@SuppressWarnings("InstantiationOfUtilityClass")
public final class UnserialisablePayload {
    private UnserialisablePayload() {
    }

    public static UnserialisablePayload create() {
        return new UnserialisablePayload();
    }

    /// Creates a [JsonBody] holding a payload, which fails to encode.
    public static JsonBody createJsonBody() {
        return new JsonBody(Json.createWriterFor(UnserialisablePayload.class), create());
    }
}
