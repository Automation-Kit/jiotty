package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;

public abstract class MultiSelectOption extends BaseOption<Set<String>> {

    private final String label;
    private final ImmutableMap<String, String> allOptions;

    protected MultiSelectOption(TaskExecutor executor, OptionMeta<Set<String>> meta, ImmutableMap<String, String> allOptions) {
        super(executor, meta);
        label = meta.label();
        checkArgument(allOptions.keySet().stream().noneMatch(id -> id.contains(",")), "Option id cannot include a comma");
        this.allOptions = ImmutableMap.copyOf(allOptions);
    }

    public String getLabel() {
        return label;
    }

    @Override
    public final CompletableFuture<Void> onFormSubmit(Optional<String> value) {
        return setValue(value.map(selectedOptionsStr -> ImmutableSet.copyOf(selectedOptionsStr.split(",")))
                             .orElse(ImmutableSet.of()));
    }

    @Override
    public OptionDtos.OptionDto toDto() {
        return new OptionDtos.MultiSelect("multiselect",
                                          meta().key(),
                                          label,
                                          meta().tabName(),
                                          getFormOrder(),
                                          allOptions,
                                          getValue().map(List::copyOf).orElseGet(List::of));
    }
}
