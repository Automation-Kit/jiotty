package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class SelectOption extends BaseOption<String> {

    private final String label;
    private final List<String> options;

    protected SelectOption(TaskExecutor executor, OptionMeta<String> meta, Iterable<String> options) {
        super(executor, meta);
        label = meta().label();
        this.options = ImmutableList.copyOf(options);
    }

    public String getLabel() {
        return label;
    }

    @Override
    public final CompletableFuture<Void> onFormSubmit(Optional<String> value) {
        return setValue(value.orElse(null));
    }

    @Override
    public OptionDtos.OptionDto toDto() {
        return new OptionDtos.Select("select", meta().key(), label, meta().tabName(), getFormOrder(), options, getValue().orElse(null));
    }
}
