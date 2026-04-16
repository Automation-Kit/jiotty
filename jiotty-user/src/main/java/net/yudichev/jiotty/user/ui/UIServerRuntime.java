package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

import java.io.IOException;

public interface UIServerRuntime {
    void handleOptionsPost(HttpServletRequest request, HttpServletResponse response);

    void handleGetDisplayablesList(HttpServletResponse response) throws IOException;

    void handleGetDisplayableItem(HttpServletRequest request, HttpServletResponse response) throws IOException;

    void handleDownload(HttpServletRequest request, HttpServletResponse response);

    Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException;

    void handlePushDeviceRegister(HttpServletRequest request, HttpServletResponse response);

    void handlePushDeviceUnregister(String deviceId, HttpServletRequest request, HttpServletResponse response);
}
