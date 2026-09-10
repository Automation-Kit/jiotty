package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.time.FriendlyDurationFormat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.EvenMoreObjects.mapIfNotNull;

/// Option for editing a time interval (duration).
///
/// HTML has no native duration input, so we use a single-line text field with a friendly, flexible syntax:
///
///   -  "HH:MM" or "HH:MM:SS" (e.g. 02:30 or 12:05:10)
///   -  "Nd HH:MM[:SS]" (e.g. 1d 02:30 or 2d 00:00:15)
///   -  Unit notation in any order: "2h 30m", "90m", "3600s", "1d 2h", etc.
///   -  ISO-8601 durations like "PT2H30M" are also accepted.
///
///
/// The value is persisted as a java.time.Duration.
public abstract class DurationOption extends BaseOption<Duration> {
    /// The answer to text that names no length this option can hold. It says nothing of what was typed, so one instance serves every refusal.
    private static final FormSubmitResult INVALID_DURATION = FormSubmitResult.rejected(OptionRejectionReasons.INVALID_DURATION);

    public DurationOption(TaskExecutor executor, OptionMeta<Duration> meta) {
        super(executor, meta);
    }

    @Override
    public CompletableFuture<FormSubmitResult> onFormSubmit(Optional<String> value) {
        Duration parsed;
        try {
            parsed = value.map(String::trim)
                          .filter(s -> !s.isEmpty())
                          .map(FriendlyDurationFormat::parseHuman)
                          .orElse(null);
        } catch (IllegalArgumentException | ArithmeticException e) {
            // A length beyond what a Duration holds overflows rather than failing to parse, and is refused the same way. The parser quotes what it could not
            // read, and that is whatever the form held, so its message stays out of both the response and the log.
            return completedFuture(INVALID_DURATION);
        }
        // Clearing the option saves null, which renders as null.
        return submit(parsed, saved -> mapIfNotNull(saved, FriendlyDurationFormat::formatHuman));
    }

    @Override
    public OptionDto toDtoUnsafe() {
        String placeholder = "e.g. 1d 02:30, 2h 15m, 90m, 3600s or PT2H30M";
        String title = "Accepted: HH:MM[:SS], Nd HH:MM[:SS], unit forms (e.g. 2h 30m, 90m), or ISO-8601 (PT...)";
        return new StandardOptionDtos.Duration("duration",
                                               meta().key(),
                                               meta().label(),
                                               meta().tabName(),
                                               getFormOrder(),
                                               placeholder,
                                               title,
                                               getValue().map(FriendlyDurationFormat::formatHuman).orElse(null)
        );
    }
}
