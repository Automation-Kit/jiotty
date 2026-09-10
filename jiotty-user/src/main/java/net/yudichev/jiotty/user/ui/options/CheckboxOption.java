package net.yudichev.jiotty.user.ui.options;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;

public abstract class CheckboxOption extends BaseOption<Boolean> {
    /// The answer to a form that sent no value at all. It carries nothing of the submission, so one instance serves every refusal.
    private static final FormSubmitResult NOTHING_SUBMITTED = FormSubmitResult.rejected(OptionRejectionReasons.INVALID_VALUE);

    public CheckboxOption(TaskExecutor executor, OptionMeta<Boolean> meta) {
        super(executor, meta);
    }

    public final boolean isSet() {
        return getValue().orElse(Boolean.FALSE);
    }

    @Override
    public final CompletableFuture<FormSubmitResult> onFormSubmit(Optional<String> value) {
        // A checkbox that submitted nothing is a broken client rather than a value to interpret — neither "on" nor "off" is a safe guess for a setting.
        return value.map(submitted -> submit(Boolean.parseBoolean(submitted)))
                    .orElseGet(() -> completedFuture(NOTHING_SUBMITTED));
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.Checkbox("checkbox", meta().key(), meta().label(), meta().tabName(), getFormOrder(), isSet());
    }
}
