package net.yudichev.jiotty.user.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;

interface UIRequestAuthoriser {
    void authorise(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException;

    interface StreamInvalidationSubscription {
        Closeable subscribe(Runnable onInvalidated);
    }

    record UIRequestContext(UIServerRuntime uiServerRuntime, StreamInvalidationSubscription streamInvalidationSubscription) {
        public UIRequestContext {
            checkNotNull(uiServerRuntime, "uiServerRuntime");
            checkNotNull(streamInvalidationSubscription, "streamInvalidationSubscription");
        }

        public Closeable subscribeToInvalidation(Runnable onInvalidated) {
            checkNotNull(onInvalidated, "onInvalidated");
            return streamInvalidationSubscription.subscribe(onInvalidated);
        }
    }
}
