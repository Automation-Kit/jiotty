package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.yudichev.jiotty.user.ui.options.Views;

/// Shared Jackson configuration for every UI-layer JSON serialiser in this package. Used by SSE broadcasters, option/displayable/push HTTP handlers, and any
/// future component that writes responses to the `/ui/api/*` surface — they all need the same JDK8 + JavaTime + Guava modules and the same `Views.UI` writer
/// view.
final class UIJson {
    static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory())
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new GuavaModule())
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    static final ObjectWriter WRITER = MAPPER.writerWithView(Views.UI.class);

    private UIJson() {
    }
}
