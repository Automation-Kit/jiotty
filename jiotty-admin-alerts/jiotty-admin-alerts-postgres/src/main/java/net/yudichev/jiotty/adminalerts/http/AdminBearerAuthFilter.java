package net.yudichev.jiotty.adminalerts.http;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;

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
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;

/// Validates the `Authorization: Bearer TOKEN` header on admin endpoints and stows the `X-Grafana-User` value as a request attribute consumed by downstream
/// servlets for audit (`resolved_by`).
///
/// `X-Grafana-User` is asserted by whoever holds the bearer token and no hop on its path can vouch for it, so it is stowed as an unverified claim about who
/// acted: bounded in length, restricted to the characters a login uses, and replaced by {@value #DEFAULT_GRAFANA_USER} otherwise.
public final class AdminBearerAuthFilter implements Filter {
    static final String GRAFANA_USER_REQUEST_ATTRIBUTE = AdminBearerAuthFilter.class.getName() + ".grafanaUser";
    static final String DEFAULT_GRAFANA_USER = "unknown@grafana";
    /// Ceiling on the caller-supplied claim; a Grafana login is far shorter.
    @VisibleForTesting
    static final int MAX_CLAIMED_ACTOR_LENGTH = 128;
    private static final String BEARER_PREFIX = "Bearer ";
    /// The characters a Grafana login is made of, precomputed because this matches on every admin request.
    private static final CharMatcher CLAIMED_ACTOR_CHARACTERS = CharMatcher.inRange('a', 'z')
                                                                           .or(CharMatcher.inRange('A', 'Z'))
                                                                           .or(CharMatcher.inRange('0', '9'))
                                                                           .or(CharMatcher.anyOf("@._-+ "))
                                                                           .precomputed();

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
        request.setAttribute(GRAFANA_USER_REQUEST_ATTRIBUTE, claimedActor(request.getHeader("X-Grafana-User")));
        chain.doFilter(request, response);
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(UNAUTHORIZED_401);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        response.getWriter().print("{\"error\":\"Unauthorized\"}");
    }

    /// Constant-time comparison against the configured token to deny timing side-channels on token guesses.
    private boolean matchesExpectedToken(String candidate) {
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
    }

    /// @param headerValue the raw `X-Grafana-User` value; `null` when the caller sent no such header
    private static String claimedActor(@Nullable String headerValue) {
        if (headerValue == null) {
            return DEFAULT_GRAFANA_USER;
        }
        String claim = headerValue.strip();
        return claim.isEmpty() || claim.length() > MAX_CLAIMED_ACTOR_LENGTH || !CLAIMED_ACTOR_CHARACTERS.matchesAllOf(claim)
               ? DEFAULT_GRAFANA_USER
               : claim;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface ResolveToken {
    }
}
