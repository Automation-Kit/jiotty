package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;

/// User-facing façade for an app to register its [Displayable]s and [Option]s with this user's UI server. HTTP endpoints are not registered here — they are
/// contributed as [ApiPathHandler]s and dispatched by [UIServerRuntime].
public interface UIServer {
    Closeable registerDisplayable(Displayable displayable);

    Closeable registerOption(Option<?> option);
}
