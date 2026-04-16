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
    public void handleOptionsPost(HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handleGetDisplayablesList(HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handleGetDisplayableItem(HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handleDownload(HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handlePushDeviceRegister(HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handlePushDeviceUnregister(String deviceId, HttpServletRequest request, HttpServletResponse response) {
        throw new UnsupportedOperationException();
    }
}
