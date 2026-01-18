package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.CompletableFutures;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class CheckboxOption extends BaseOption<Boolean> {

    private final String label;

    public CheckboxOption(TaskExecutor executor, OptionMeta<Boolean> meta) {
        super(executor, meta);
        label = meta().label();
    }

    public final boolean isSet() {
        return getValue().orElse(Boolean.FALSE);
    }

    public String getLabel() {
        return label;
    }

    @Override
    public final CompletableFuture<Void> onFormSubmit(Optional<String> value) {
        return value.map(Boolean::parseBoolean).map(this::setValue).orElse(CompletableFutures.failure("expected 'true' or 'false'"));
    }

    @Override
    public OptionDtos.OptionDto toDto() {
        return new OptionDtos.Checkbox("checkbox", meta().key(), label, meta().tabName(), getFormOrder(), isSet());
    }
}
