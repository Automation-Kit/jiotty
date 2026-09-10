package net.yudichev.jiotty.user.ui.options;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

/// What [Option#onFormSubmit(Optional)] answers with. A value the option refuses is an expected outcome of a form the user filled in, not a fault, so it is one
/// of the two answers rather than a failed future.
public sealed interface FormSubmitResult {
    /// @param response the saved value as the client displays it, `null` where the option now holds nothing
    static FormSubmitResult accepted(@Nullable Object response) {
        return new Accepted(response);
    }

    static FormSubmitResult rejected(OptionRejection rejection) {
        return new Rejected(rejection);
    }

    /// Refuses with a case that carries no values of its own.
    static FormSubmitResult rejected(String reason) {
        return new Rejected(OptionRejection.of(reason));
    }

    /// The value was stored.
    ///
    /// @param response what to send back, serialised to JSON — the saved value in the form the client displays it in, or `null` where the submission
    ///                 cleared the option
    record Accepted(@Nullable Object response) implements FormSubmitResult {}

    /// The value was refused and the option still holds what it held before.
    record Rejected(OptionRejection rejection) implements FormSubmitResult {}
}
