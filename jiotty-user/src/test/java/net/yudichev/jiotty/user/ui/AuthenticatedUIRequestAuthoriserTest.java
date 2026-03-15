package net.yudichev.jiotty.user.ui;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.persistence.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticatedUIRequestAuthoriserTest {
    private static final UserProfile PROFILE = new UserProfile("user-1",
                                                               Optional.of("user@example.com"),
                                                               Optional.of("Alex"),
                                                               ZoneId.of("Europe/London"),
                                                               Instant.parse("2026-03-13T12:00:00Z"),
                                                               Instant.parse("2026-03-13T12:00:00Z"));

    @Mock
    private UserTokenAuthoriser userTokenAuthoriser;
    private AuthenticatedUIRequestAuthoriser authoriser;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private AsyncContext asyncContext;
    @Mock
    private FilterChain chain;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() {
        authoriser = new AuthenticatedUIRequestAuthoriser(userTokenAuthoriser);
    }

    @Test
    void rejectsMissingBearerToken() throws Exception {
        prepareResponseBody();

        authoriser.authorise(request, response, chain);

        verify(response).setStatus(401);
        verify(userTokenAuthoriser, never()).deliverTokenState(anyString(), any());
        assertThat(responseBody.toString()).contains("INVALID").contains("Missing or invalid Authorization bearer token");
    }

    @ParameterizedTest
    @EnumSource(UserTokenAuthoriser.TokenNotAuthenticated.Reason.class)
    void mapsNotAuthenticatedStatesToHttpStatuses(UserTokenAuthoriser.TokenNotAuthenticated.Reason reason) throws Exception {
        prepareAsyncContext();
        prepareResponseBody();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        doAnswer(invocation -> {
            Consumer<? super UserTokenAuthoriser.TokenState> handler = invocation.getArgument(1);
            handler.accept(new UserTokenAuthoriser.TokenNotAuthenticated(reason, "failure"));
            return null;
        }).when(userTokenAuthoriser).deliverTokenState(eq("token-1"), any());

        authoriser.authorise(request, response, chain);

        verify(asyncContext).setTimeout(0);
        verify(asyncContext).complete();
        verify(response).setStatus(expectedStatus(reason));
        assertThat(responseBody.toString()).contains(reason.name()).contains("failure");
    }

    @Test
    void createsRequestContextAndProvidesInvalidationSubscription() throws Exception {
        prepareAsyncContext();
        prepareRequestContextAttributes();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        var uiServer = new FakeUIServer();
        var tokenStateSubscriptionHandler = new MutableReference<Consumer<? super UserTokenAuthoriser.TokenState>>();
        doAnswer(invocation -> {
            Consumer<? super UserTokenAuthoriser.TokenState> handler = invocation.getArgument(1);
            handler.accept(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, uiServer));
            return null;
        }).when(userTokenAuthoriser).deliverTokenState(eq("token-1"), any());
        when(userTokenAuthoriser.subscribeToTokenState(eq("token-1"), any())).thenAnswer(invocation -> {
            tokenStateSubscriptionHandler.set(invocation.getArgument(1));
            return Closeable.noop();
        });

        authoriser.authorise(request, response, chain);

        verify(asyncContext).dispatch();
        assertThat(UIHttpServerImpl.requestContext(request).uiServerRuntime()).isSameAs(uiServer);

        var invalidated = new MutableReference<>(false);
        Closeable subscription = UIHttpServerImpl.requestContext(request).subscribeToInvalidation(() -> invalidated.set(true));
        tokenStateSubscriptionHandler.get().accept(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, uiServer));
        assertThat(invalidated.get()).isFalse();

        tokenStateSubscriptionHandler.get()
                                     .accept(new UserTokenAuthoriser.TokenNotAuthenticated(UserTokenAuthoriser.TokenNotAuthenticated.Reason.INVALID,
                                                                                           "expired"));
        assertThat(invalidated.get()).isTrue();
        subscription.close();
    }

    private void prepareAsyncContext() {
        when(request.startAsync()).thenReturn(asyncContext);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
    }

    private void prepareResponseBody() throws Exception {
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    private void prepareRequestContextAttributes() {
        var requestAttributes = new HashMap<String, Object>();
        doAnswer(invocation -> {
            requestAttributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString())).thenAnswer(invocation -> {
            String attrName = invocation.getArgument(0);
            return requestAttributes.get(attrName);
        });
    }

    private static int expectedStatus(UserTokenAuthoriser.TokenNotAuthenticated.Reason reason) {
        return switch (reason) {
            case INVALID -> 401;
            case USER_DISABLED -> 403;
            case TECHNICAL_FAILURE -> 503;
        };
    }
}
