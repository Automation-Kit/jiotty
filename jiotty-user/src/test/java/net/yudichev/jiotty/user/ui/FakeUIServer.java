package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;

final class FakeUIServer implements UIServer, UIServerRuntime {
    @Override
    public Closeable registerDisplayable(Displayable displayable) {
        return Closeable.noop();
    }

    @Override
    public Closeable registerOption(Option<?> option) {
        return Closeable.noop();
    }

    @Override
    public DispatchResult dispatchApiPath(HttpServletRequest request, HttpServletResponse response) {
        return DispatchResult.NOT_FOUND;
    }
}
