package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenAuthenticated;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenNotAuthenticated;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenNotAuthenticated.Reason;

final class AuthenticatedUIRequestAuthoriser implements UIRequestAuthoriser {
    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory());

    private final UserTokenAuthoriser userTokenAuthoriser;

    @Inject
    AuthenticatedUIRequestAuthoriser(@Dependency UserTokenAuthoriser userTokenAuthoriser) {
        this.userTokenAuthoriser = checkNotNull(userTokenAuthoriser, "userTokenAuthoriser");
    }

    @Override
    public void authorise(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        String token = bearerToken(request);
        if (token == null) {
            writeAuthenticationFailure(response,
                                       new TokenNotAuthenticated(Reason.INVALID, "Missing or invalid Authorization bearer token"));
            return;
        }

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);
        userTokenAuthoriser.deliverTokenState(token, state -> asyncContext.start(() -> {
            switch (state) {
                case TokenAuthenticated tokenAuthenticated -> {
                    UIHttpServerImpl.setRequestContext(request, createRequestContext(token, tokenAuthenticated));
                    asyncContext.dispatch();
                }
                case TokenNotAuthenticated tokenNotAuthenticated -> {
                    asUnchecked(() -> writeAuthenticationFailure(response, tokenNotAuthenticated));
                    asyncContext.complete();
                }
            }
        }));
    }

    private UIRequestContext createRequestContext(String token, TokenAuthenticated tokenAuthenticated) {
        return new UIRequestContext(runtime(tokenAuthenticated),
                                    onInvalidated -> userTokenAuthoriser.subscribeToTokenState(token, state -> {
                                        switch (state) {
                                            case TokenAuthenticated _ -> {
                                            }
                                            case TokenNotAuthenticated _ -> onInvalidated.run();
                                        }
                                    }));
    }

    private static UIServerRuntime runtime(TokenAuthenticated tokenAuthenticated) {
        UIServer uiServer = tokenAuthenticated.uiServer();
        assert uiServer instanceof UIServerRuntime : "Unexpected UI server implementation: " + uiServer.getClass().getName();
        return (UIServerRuntime) uiServer;
    }

    private static @Nullable String bearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null) {
            return null;
        }
        if (!authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        return token.isBlank() ? null : token;
    }

    private static void writeAuthenticationFailure(HttpServletResponse response, TokenNotAuthenticated state) throws IOException {
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        response.setStatus(switch (state.reason()) {
            case INVALID -> 401;
            case USER_DISABLED -> 403;
            case TECHNICAL_FAILURE -> 503;
        });
        MAPPER.writeValue(response.getWriter(),
                          Map.of("reason", state.reason().name(),
                                 "technicalDescription", state.technicalDescription()));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
