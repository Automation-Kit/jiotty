package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/// @param groups history groups by a 'what' key, each with a list of entries (time + text)
public record HistoryDisplayableDto(Map<String, List<Entry>> groups) implements DisplayableDto {
    @Override
    @JsonProperty("type")
    public String type() {
        return "history";
    }

    public record Entry(Instant time, Format format, Object value) {}

    /// Text format for displayable history entries.
    public enum Format {
        PLAIN_TEXT,
        HTML,
        OBJECT
    }
}
