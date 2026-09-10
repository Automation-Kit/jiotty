package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.common.lang.EvenMoreObjects.mapIfNotNull;

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
            return failure(OptionValueRejectedException.of(OptionRejectionReasons.INVALID_TIME, e));
        }
        // Clearing the option saves null, which renders as null.
        return setValue(localTime).thenApply(saved -> mapIfNotNull(saved, LocalTime::toString));
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
