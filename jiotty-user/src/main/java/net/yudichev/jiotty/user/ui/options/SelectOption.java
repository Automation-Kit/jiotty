package net.yudichev.jiotty.user.ui.options;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class SelectOption extends BaseOption<String> {

    private final List<String> options;

    protected SelectOption(TaskExecutor executor, OptionMeta<String> meta, Iterable<String> options) {
        super(executor, meta);
        this.options = ImmutableList.copyOf(options);
    }

    @Override
    public final CompletableFuture<?> onFormSubmit(Optional<String> value) {
        return setValue(value.orElse(null));
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.Select("select", meta().key(), meta().label(), meta().tabName(), getFormOrder(), options, getValue().orElse(null));
    }
}
