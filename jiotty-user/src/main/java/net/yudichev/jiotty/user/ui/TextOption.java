package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class TextOption extends BaseOption<String> {
    private final String label;

    protected TextOption(TaskExecutor executor, OptionMeta<String> meta) {
        super(executor, meta);
        label = meta.label();
    }

    public String getLabel() {
        return label;
    }

    @Override
    public CompletableFuture<Void> onFormSubmit(Optional<String> value) {
        return setValue(value.orElse(null));
    }

    @Override
    public OptionDtos.OptionDto toDto() {
        return new OptionDtos.Text("text", meta().key(), label, meta().tabName(), getFormOrder(), getValue().orElse(null));
    }
}
