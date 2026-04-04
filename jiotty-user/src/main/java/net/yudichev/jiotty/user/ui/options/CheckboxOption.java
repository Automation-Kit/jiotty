package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.CompletableFutures;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class CheckboxOption extends BaseOption<Boolean> {

    public CheckboxOption(TaskExecutor executor, OptionMeta<Boolean> meta) {
        super(executor, meta);
    }

    public final boolean isSet() {
        return getValue().orElse(Boolean.FALSE);
    }

    @Override
    public final CompletableFuture<?> onFormSubmit(Optional<String> value) {
        return value.map(Boolean::parseBoolean)
                    .map(this::setValue)
                    .orElse(CompletableFutures.failure("expected 'true' or 'false'"));
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.Checkbox("checkbox", meta().key(), meta().label(), meta().tabName(), getFormOrder(), isSet());
    }
}
