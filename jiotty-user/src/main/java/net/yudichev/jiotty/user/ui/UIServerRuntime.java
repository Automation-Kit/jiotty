package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

import java.io.IOException;

public interface UIServerRuntime {
    void handleOptionsPost(HttpServletRequest request, HttpServletResponse response);

    void writeDisplayablesListJson(HttpServletResponse response) throws IOException;

    void writeDisplayableItemJson(HttpServletRequest request, HttpServletResponse response) throws IOException;

    void handleDownload(HttpServletRequest request, HttpServletResponse response);

    Closeable startDisplayablesSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException;
}
