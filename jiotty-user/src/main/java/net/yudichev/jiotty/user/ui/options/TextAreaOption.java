package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public abstract class TextAreaOption extends BaseOption<String> {
    private static final Pattern LINES_PATTERN = Pattern.compile("[\\n\\r]+");

    protected int rowCount = 3;

    protected TextAreaOption(TaskExecutor executor, OptionMeta<String> meta) {
        super(executor, meta);
    }

    @Override
    public CompletableFuture<?> onFormSubmit(Optional<String> value) {
        return setValue(value.orElse(null));
    }

    public List<String> getTrimmedNonBlankLines() {
        return getValue().map(value -> Stream.of(LINES_PATTERN.split(value))
                                             .map(String::trim)
                                             .filter(s -> !s.isEmpty())
                                             .toList())
                         .orElse(List.of());
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.TextArea("textarea", meta().key(), meta().label(), meta().tabName(), getFormOrder(), rowCount, getValue().orElse(""));
    }
}
