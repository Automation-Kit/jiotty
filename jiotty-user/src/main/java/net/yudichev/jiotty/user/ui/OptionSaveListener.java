package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.user.ui.options.Option;

/// A listener that is invoked with an [Option] each time an [OptionRegistry] saves it: once when the option is registered, and again after each change to it.
public interface OptionSaveListener {
    OptionSaveListener NO_OP = _ -> {};

    /// @param option the option as it was saved
    /// @implSpec Implementations must return without blocking, because the registry invokes this on the thread that changed the option.
    void onSaved(Option<?> option);
}
