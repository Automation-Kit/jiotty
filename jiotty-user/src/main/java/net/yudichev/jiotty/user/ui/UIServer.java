package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;

public interface UIServer {
    Closeable registerDisplayable(Displayable displayable);

    Closeable registerOption(Option<?> option);
}
