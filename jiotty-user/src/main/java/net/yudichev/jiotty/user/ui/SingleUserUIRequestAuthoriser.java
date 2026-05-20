package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;

final class SingleUserUIRequestAuthoriser implements UIRequestAuthoriser {
    private final UIRequestContext requestContext;

    @Inject
    SingleUserUIRequestAuthoriser(UIServer uiServer) {
        checkNotNull(uiServer, "uiServer");
        assert uiServer instanceof UIServerRuntime : "Unexpected UI server implementation: " + uiServer;
        requestContext = new UIRequestContext((UIServerRuntime) uiServer, _ -> Closeable.noop());
    }

    @Override
    public void authorise(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        RequestContextFilter.setRequestContext(request, requestContext);
        chain.doFilter(request, response);
    }
}
