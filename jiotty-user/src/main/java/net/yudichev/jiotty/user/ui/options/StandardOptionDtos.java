package net.yudichev.jiotty.user.ui.options;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.geo.LatLon;

import java.util.List;
import java.util.Map;

public final class StandardOptionDtos {
    private StandardOptionDtos() {
    }

    public record Text(String type, String key, String label, String tabName, int order, String value) implements OptionDto {}

    public record TextArea(String type, String key, String label, String tabName, int order, int rows, String value) implements OptionDto {}

    public record Checkbox(String type, String key, String label, String tabName, int order, boolean checked) implements OptionDto {}

    public record Time(String type, String key, String label, String tabName, int order, String value) implements OptionDto {}

    public record Duration(String type, String key, String label, String tabName, int order, String placeholder, String help, String valueHuman)
            implements OptionDto {}

    public record Select(String type, String key, String label, String tabName, int order, List<String> options, String value) implements OptionDto {}

    public record MultiSelect(String type, String key, String label, String tabName, int order, Map<String, String> allOptions, List<String> selectedIds)
            implements OptionDto {}

    public record Location(String type, String key, String label, String tabName, int order, @Nullable LatLon value)
            implements OptionDto {}

}
