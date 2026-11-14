package net.yudichev.jiotty.connector.miele;

import net.yudichev.jiotty.common.lang.Json;

import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// Device payloads the tests read from the test classpath.
final class MieleTestResources {
    /// A device running the Auto program with 3 h 11 min remaining.
    static final String RUNNING_DEVICE = "/miele-device-1.json";
    /// A device reported as not connected, with no remaining time.
    static final String NOT_CONNECTED_DEVICE = "/miele-device-2.json";

    private MieleTestResources() {
    }

    /// @return the content of the resource `name`
    static String read(String name) {
        return getAsUnchecked(() -> {
            try (InputStream resourceAsStream = MieleTestResources.class.getResourceAsStream(name)) {
                assert resourceAsStream != null : name;
                return new String(resourceAsStream.readAllBytes(), UTF_8);
            }
        });
    }

    /// @return the content of the resource `name` parsed as `type`
    static <T> T parse(String name, Class<T> type) {
        return Json.parse(read(name), type);
    }
}
