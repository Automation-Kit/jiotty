package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public abstract class TextAreaOption extends BaseOption<String> {
    private static final Pattern LINES_PATTERN = Pattern.compile("[\\n\\r]+");

    private final String label;
    protected int rowCount = 3;

    protected TextAreaOption(TaskExecutor executor, OptionMeta<String> meta) {
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

    public List<String> getTrimmedNonBlankLines() {
        return getValue().map(value -> Stream.of(LINES_PATTERN.split(value))
                                             .map(String::trim)
                                             .filter(s -> !s.isEmpty())
                                             .toList())
                         .orElse(List.of());
    }

    @Override
    public OptionDtos.OptionDto toDto() {
        return new OptionDtos.TextArea("textarea", meta().key(), label, meta().tabName(), getFormOrder(), getValue().orElse(""), rowCount);
    }
}
