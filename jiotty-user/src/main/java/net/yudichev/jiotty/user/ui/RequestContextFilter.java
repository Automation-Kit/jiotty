package net.yudichev.jiotty.user.ui;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/// Resolves the per-user [UIRequestContext] for requests reaching a mount that installs this filter, and attaches it to the request as an attribute.
///
/// Mounts that need the per-user runtime install this filter; mounts that serve only public resources do not. Re-entry from an [AsyncContext#dispatch] is
/// detected via the attribute already being set, in which case the chain proceeds directly to the servlet.
final class RequestContextFilter implements Filter {
    static final String REQUEST_CONTEXT_ATTRIBUTE = RequestContextFilter.class.getName() + ".requestContext";

    private final UIRequestAuthoriser requestAuthoriser;

    RequestContextFilter(UIRequestAuthoriser requestAuthoriser) {
        this.requestAuthoriser = checkNotNull(requestAuthoriser, "requestAuthoriser");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        checkArgument(servletRequest instanceof HttpServletRequest, "Expected HttpServletRequest");
        checkArgument(servletResponse instanceof HttpServletResponse, "Expected HttpServletResponse");
        var request = (HttpServletRequest) servletRequest;
        var response = (HttpServletResponse) servletResponse;
        if (request.getAttribute(REQUEST_CONTEXT_ATTRIBUTE) != null) {
            chain.doFilter(request, response);
            return;
        }
        requestAuthoriser.authorise(request, response, chain);
    }

    static void setRequestContext(HttpServletRequest request, UIRequestContext requestContext) {
        request.setAttribute(REQUEST_CONTEXT_ATTRIBUTE, requestContext);
    }

    static UIRequestContext requestContext(HttpServletRequest request) {
        Object requestContext = request.getAttribute(REQUEST_CONTEXT_ATTRIBUTE);
        checkState(requestContext instanceof UIRequestContext, "Request context is not initialised");
        return (UIRequestContext) requestContext;
    }
}
