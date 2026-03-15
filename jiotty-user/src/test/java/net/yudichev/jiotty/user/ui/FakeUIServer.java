package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

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
    public void handleOptionsPost(HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeOptionsJson(HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeDisplayablesListJson(HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeDisplayableItemJson(HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handleDownload(HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Closeable startDisplayablesSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) {
        throw new UnsupportedOperationException();
    }
}
