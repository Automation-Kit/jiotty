package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.EvenMoreObjects.mapIfNotNull;

public abstract class TimeOption extends BaseOption<LocalTime> {
    /// The answer to text that names no time of day. It says nothing of what was typed, so one instance serves every refusal.
    private static final FormSubmitResult INVALID_TIME = FormSubmitResult.rejected(OptionRejectionReasons.INVALID_TIME);

    protected TimeOption(TaskExecutor executor, OptionMeta<LocalTime> meta) {
        super(executor, meta);
    }

    @Override
    public CompletableFuture<FormSubmitResult> onFormSubmit(Optional<String> value) {
        LocalTime localTime;
        try {
            localTime = value.map(LocalTime::parse).orElse(null);
        } catch (DateTimeParseException e) {
            // The parser quotes what it could not read, and that is whatever the form held, so its message stays out of both the response and the log.
            return completedFuture(INVALID_TIME);
        }
        // Clearing the option saves null, which renders as null.
        return submit(localTime, saved -> mapIfNotNull(saved, LocalTime::toString));
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
