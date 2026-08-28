package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/// The serialisation every var store shares, including the in-memory test double; kept apart from [net.yudichev.jiotty.common.lang.Json] because these
/// settings govern what is written to disk, where changing one rewrites stored data.
///
/// Temporal values are written as ISO-8601 strings because [VarStore#exportEntries] reports a stored value verbatim in the GDPR Art. 15 archive, where
/// `[2026, 8, 27]` is unintelligible to a data subject.
final class VarStoreJson {
    public static final ObjectMapper COMPACT = newMapper();
    public static final ObjectMapper INDENTED = newMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private VarStoreJson() {
    }

    private static ObjectMapper newMapper() {
        return new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .registerModule(new GuavaModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Jackson gates durations behind their own feature, and a stored duration is exported like every other value.
                .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    }
}
