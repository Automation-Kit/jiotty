package net.yudichev.jiotty.adminalerts.http;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Validates the `Authorization: Bearer TOKEN` header on admin endpoints and stows the `X-Grafana-User` value as a request attribute consumed by downstream
/// servlets for audit (`resolved_by`).
public final class AdminBearerAuthFilter implements Filter {
    static final String GRAFANA_USER_REQUEST_ATTRIBUTE = AdminBearerAuthFilter.class.getName() + ".grafanaUser";
    static final String DEFAULT_GRAFANA_USER = "unknown@grafana";

    private static final String BEARER_PREFIX = "Bearer ";

    private final String expectedToken;

    @Inject
    public AdminBearerAuthFilter(@ResolveToken String expectedToken) {
        this.expectedToken = checkNotNull(expectedToken, "expectedToken");
        checkArgument(!expectedToken.isBlank(), "expectedToken must not be blank");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        checkArgument(servletRequest instanceof HttpServletRequest, "Expected HttpServletRequest");
        checkArgument(servletResponse instanceof HttpServletResponse, "Expected HttpServletResponse");
        var request = (HttpServletRequest) servletRequest;
        var response = (HttpServletResponse) servletResponse;
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response);
            return;
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (!matchesExpectedToken(token)) {
            writeUnauthorized(response);
            return;
        }
        String grafanaUser = request.getHeader("X-Grafana-User");
        request.setAttribute(GRAFANA_USER_REQUEST_ATTRIBUTE,
                             (grafanaUser == null || grafanaUser.isBlank()) ? DEFAULT_GRAFANA_USER : grafanaUser);
        chain.doFilter(request, response);
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        response.getWriter().print("{\"error\":\"Unauthorized\"}");
    }

    /// Constant-time comparison against the configured token to deny timing side-channels on token guesses.
    private boolean matchesExpectedToken(String candidate) {
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface ResolveToken {
    }
}
