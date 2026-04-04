package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class TextOption extends BaseOption<String> {
    protected TextOption(TaskExecutor executor, OptionMeta<String> meta) {
        super(executor, meta);
    }

    @Override
    public CompletableFuture<?> onFormSubmit(Optional<String> value) {
        return setValue(value.orElse(null));
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.Text("text", meta().key(), meta().label(), meta().tabName(), getFormOrder(), getValue().orElse(null));
    }
}
