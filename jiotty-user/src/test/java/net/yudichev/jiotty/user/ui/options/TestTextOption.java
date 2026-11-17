package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;
import org.jspecify.annotations.Nullable;

/// A [TextOption] whose [#onChanged()] returns the value that it holds, unchanged.
public final class TestTextOption extends TextOption {
    public TestTextOption(TaskExecutor executor, OptionMeta<String> meta) {
        super(executor, meta);
    }

    @Override
    public @Nullable String onChanged() {
        return value();
    }
}
