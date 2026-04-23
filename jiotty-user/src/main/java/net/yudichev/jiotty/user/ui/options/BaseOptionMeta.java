package net.yudichev.jiotty.user.ui.options;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import static net.yudichev.jiotty.user.ui.options.Option.DEFAULT_FORM_ORDER;

@Immutable
@PublicImmutablesStyle
public interface BaseOptionMeta<T> {
    String tabName();

    String key();

    String label();

    @Nullable T defaultValue();

    /// See [Option#getFormOrder()]. Defaults to [Option#DEFAULT_FORM_ORDER].
    @Value.Default
    default int formOrder() {
        return DEFAULT_FORM_ORDER;
    }

    /// Whether the option's persisted value must be encrypted at rest.
    @Value.Default
    default boolean sensitive() {
        return false;
    }
}
