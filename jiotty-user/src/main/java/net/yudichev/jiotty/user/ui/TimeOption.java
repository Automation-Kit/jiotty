package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.CompletableFutures;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class TimeOption extends BaseOption<LocalTime> {
    private final String label;

    protected TimeOption(TaskExecutor executor, OptionMeta<LocalTime> meta) {
        super(executor, meta);
        label = meta.label();
    }

    public String getLabel() {
        return label;
    }

    @Override
    public CompletableFuture<Void> onFormSubmit(Optional<String> value) {
        LocalTime localTime;
        try {
            localTime = value.map(LocalTime::parse).orElse(null);
        } catch (DateTimeParseException e) {
            return CompletableFutures.failure("Invalid time: '" + e.getParsedString() + "'");
        }
        return setValue(localTime);
    }

    @Override
    public OptionDtos.OptionDto toDto() {
        return new OptionDtos.Time("time", meta().key(), label, meta().tabName(), getFormOrder(), getValue().map(LocalTime::toString).orElse(null));
    }
}
