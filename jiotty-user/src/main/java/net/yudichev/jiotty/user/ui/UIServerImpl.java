package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;

import static com.google.common.base.Preconditions.checkNotNull;

/// User-facing [UIServer] façade: an app registers its [Displayable]s and [Option]s here, and the calls delegate to the underlying registries. Request
/// dispatch is a separate concern handled by [UIServerRuntime].
public final class UIServerImpl implements UIServer {
    private final OptionRegistry optionRegistry;
    private final DisplayableRegistry displayableRegistry;

    @Inject
    public UIServerImpl(OptionRegistry optionRegistry, DisplayableRegistry displayableRegistry) {
        this.optionRegistry = checkNotNull(optionRegistry, "optionRegistry");
        this.displayableRegistry = checkNotNull(displayableRegistry, "displayableRegistry");
    }

    @Override
    public Closeable registerDisplayable(Displayable displayable) {
        return displayableRegistry.register(displayable);
    }

    @Override
    public Closeable registerOption(Option<?> option) {
        return optionRegistry.register(option);
    }
}
