package net.yudichev.jiotty.user.ui.options;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;

public abstract class MultiSelectOption extends BaseOption<Set<String>> {

    private final ImmutableMap<String, String> allOptions;
    private final boolean allOptionsComplete;

    protected MultiSelectOption(TaskExecutor executor, OptionMeta<Set<String>> meta, ImmutableMap<String, String> allOptions) {
        this(executor, meta, allOptions, true);
    }

    /// @param allOptionsComplete whether `allOptions` lists every available choice; `false` while a progressively-loaded choice list is still being assembled,
    ///                           telling consumers that an id absent from `allOptions` may reappear
    protected MultiSelectOption(TaskExecutor executor, OptionMeta<Set<String>> meta, ImmutableMap<String, String> allOptions, boolean allOptionsComplete) {
        super(executor, meta);
        checkArgument(allOptions.keySet().stream().noneMatch(id -> id.contains(",")), "Option id cannot include a comma");
        this.allOptions = ImmutableMap.copyOf(allOptions);
        this.allOptionsComplete = allOptionsComplete;
    }

    @Override
    public final CompletableFuture<?> onFormSubmit(Optional<String> value) {
        return setValue(value.filter(s -> !s.isEmpty())
                             .map(selectedOptionsStr -> ImmutableSet.copyOf(selectedOptionsStr.split(",")))
                             .orElse(ImmutableSet.of()));
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.MultiSelect("multiselect",
                                                  meta().key(),
                                                  meta().label(),
                                                  meta().tabName(),
                                                  getFormOrder(),
                                                  allOptions,
                                                  allOptionsComplete,
                                                  getValue().map(List::copyOf).orElseGet(List::of));
    }
}
