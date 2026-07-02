package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.annotation.JsonProperty;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.security.LogRedaction;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

/// The group keys and plain string entry values are user-identifying, so they are redacted when the DTO is rendered for logging via [Object#toString()] /
/// [StringFormattable#formatTo(Appendable)]; a formattable entry value (e.g. a recordable) renders itself, redacting its own PII. The real values are only
/// carried by the record components themselves.
///
/// @param groups history groups by a 'what' key, each with a list of entries (time + value)
public record HistoryDisplayableDto(Map<String, List<Entry>> groups) implements DisplayableDto, StringFormattable {
    @Override
    @JsonProperty("type")
    public String type() {
        return "history";
    }

    @Override
    public String toString() {
        return toString(256);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "HistoryDisplayableDto[groups=");
        Append.to(appendable, groups, LogRedaction::appendRedacted, (a, entries) -> Append.to(a, entries, HistoryDisplayableDto::appendEntry));
        Append.to(appendable, ']');
    }

    private static void appendEntry(Appendable appendable, Entry entry) {
        Append.to(appendable, "Entry[time=");
        Append.to(appendable, entry.time());
        Append.to(appendable, ", format=");
        Append.to(appendable, entry.format());
        Append.to(appendable, ", value=");
        Object value = entry.value();
        switch (value) {
            case null -> Append.to(appendable, "null");
            case StringFormattable formattable -> formattable.formatTo(appendable);
            default -> appendRedacted(appendable, String.valueOf(value));
        }
        Append.to(appendable, ']');
    }

    public record Entry(Instant time, Format format, Object value) {}

    /// Text format for displayable history entries.
    public enum Format {
        PLAIN_TEXT,
        HTML,
        OBJECT
    }
}
