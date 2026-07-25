package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.security.LogRedaction;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

/// These DTOs are rendered for logging via [Object#toString()] / [StringFormattable#formatTo(Appendable)] with only their PII-bearing values redacted: the
/// values a user typed or picked that can carry personal data — [Text]/[TextArea]/[Time] `value`, the [Location] coordinate, the [Select] `options` and
/// `value` (a select's choices can be populated from user data), and the [MultiSelect] calendar/driver ids and names. Only the structural fields every
/// option carries (`type`, `key`, `label`, `tabName`, `order`), the app-defined [Duration] hint fields (`placeholder`, `help`, `valueHuman`) and the
/// [MultiSelect] `allOptionsComplete` flag are fixed, non-identifying values logged verbatim. The real values are always carried by the record components
/// themselves.
public final class StandardOptionDtos {
    private StandardOptionDtos() {
    }

    private static void appendCommon(Appendable appendable, String recordName, String type, String key, String label, String tabName, int order) {
        Append.to(appendable, recordName);
        Append.to(appendable, "[type=");
        Append.to(appendable, type);
        Append.to(appendable, ", key=");
        Append.to(appendable, key);
        Append.to(appendable, ", label=");
        Append.to(appendable, label);
        Append.to(appendable, ", tabName=");
        Append.to(appendable, tabName);
        Append.to(appendable, ", order=");
        Append.to(appendable, order);
    }

    public record Text(String type, String key, String label, String tabName, int order, String value) implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(96);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "Text", type, key, label, tabName, order);
            Append.to(appendable, ", value=");
            appendRedacted(appendable, value);
            Append.to(appendable, ']');
        }
    }

    public record TextArea(String type, String key, String label, String tabName, int order, int rows, String value)
            implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(96);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "TextArea", type, key, label, tabName, order);
            Append.to(appendable, ", rows=");
            Append.to(appendable, rows);
            Append.to(appendable, ", value=");
            appendRedacted(appendable, value);
            Append.to(appendable, ']');
        }
    }

    public record Checkbox(String type, String key, String label, String tabName, int order, boolean checked) implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(96);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "Checkbox", type, key, label, tabName, order);
            Append.to(appendable, ", checked=");
            Append.to(appendable, checked);
            Append.to(appendable, ']');
        }
    }

    public record Time(String type, String key, String label, String tabName, int order, String value) implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(96);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "Time", type, key, label, tabName, order);
            Append.to(appendable, ", value=");
            appendRedacted(appendable, value);
            Append.to(appendable, ']');
        }
    }

    public record Duration(String type, String key, String label, String tabName, int order, String placeholder, String help, String valueHuman)
            implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(128);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "Duration", type, key, label, tabName, order);
            Append.to(appendable, ", placeholder=");
            Append.to(appendable, placeholder);
            Append.to(appendable, ", help=");
            Append.to(appendable, help);
            Append.to(appendable, ", valueHuman=");
            Append.to(appendable, valueHuman);
            Append.to(appendable, ']');
        }
    }

    public record Select(String type, String key, String label, String tabName, int order, List<String> options, String value)
            implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(128);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "Select", type, key, label, tabName, order);
            Append.to(appendable, ", options=");
            Append.to(appendable, options, LogRedaction::appendRedacted);
            Append.to(appendable, ", value=");
            appendRedacted(appendable, value);
            Append.to(appendable, ']');
        }
    }

    public record MultiSelect(String type,
                              String key,
                              String label,
                              String tabName,
                              int order,
                              Map<String, String> allOptions,
                              boolean allOptionsComplete,
                              List<String> selectedIds)
            implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(192);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "MultiSelect", type, key, label, tabName, order);
            Append.to(appendable, ", allOptions=");
            Append.to(appendable, allOptions, LogRedaction::appendRedacted, LogRedaction::appendRedacted);
            Append.to(appendable, ", allOptionsComplete=");
            Append.to(appendable, allOptionsComplete);
            Append.to(appendable, ", selectedIds=");
            Append.to(appendable, selectedIds, LogRedaction::appendRedacted);
            Append.to(appendable, ']');
        }
    }

    public record Location(String type, String key, String label, String tabName, int order, @Nullable LatLon value)
            implements OptionDto, StringFormattable {
        @Override
        public String toString() {
            return toString(96);
        }

        @Override
        public void formatTo(Appendable appendable) {
            appendCommon(appendable, "Location", type, key, label, tabName, order);
            Append.to(appendable, ", value=");
            appendRedacted(appendable, value);
            Append.to(appendable, ']');
        }
    }
}
