package net.yudichev.jiotty.user.ui;

import jakarta.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.user.ui.Option.DEFAULT_FORM_ORDER;

/// @param formOrder see [Option#getFormOrder() ]
public record OptionMeta<T>(int formOrder, String tabName, String key, String label, T defaultValue) {
    public OptionMeta(String tabName, String key, String label, @Nullable T defaultValue) {
        this(DEFAULT_FORM_ORDER, tabName, key, label, defaultValue);
    }

    public OptionMeta {
        checkNotNull(tabName);
        checkNotNull(key);
        checkNotNull(label);
    }
}
