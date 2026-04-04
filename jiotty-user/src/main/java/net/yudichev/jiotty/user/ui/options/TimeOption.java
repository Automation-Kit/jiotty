package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.CompletableFutures;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class TimeOption extends BaseOption<LocalTime> {
    protected TimeOption(TaskExecutor executor, OptionMeta<LocalTime> meta) {
        super(executor, meta);
    }

    @Override
    public CompletableFuture<?> onFormSubmit(Optional<String> value) {
        LocalTime localTime;
        try {
            localTime = value.map(LocalTime::parse).orElse(null);
        } catch (DateTimeParseException e) {
            return CompletableFutures.failure("Invalid time: '" + e.getParsedString() + "'");
        }
        return setValue(localTime).thenApply(LocalTime::toString);
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.Time("time",
                                           meta().key(),
                                           meta().label(),
                                           meta().tabName(),
                                           getFormOrder(),
                                           getValue().map(LocalTime::toString).orElse(null));
    }
}
