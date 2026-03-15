package net.yudichev.jiotty.user.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SingleUserUIRequestAuthoriserTest {
    private FakeUIServer uiServer;
    private SingleUserUIRequestAuthoriser authoriser;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        uiServer = new FakeUIServer();
        authoriser = new SingleUserUIRequestAuthoriser(uiServer);

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

    @Test
    void attachesSingletonRuntimeAndCallsChain() throws Exception {
        authoriser.authorise(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(UIHttpServerImpl.requestContext(request).uiServerRuntime()).isSameAs(uiServer);

        var invalidated = new MutableReference<>(false);
        Closeable subscription = UIHttpServerImpl.requestContext(request).subscribeToInvalidation(() -> invalidated.set(true));
        assertThatNoException().isThrownBy(subscription::close);
        assertThat(invalidated.get()).isFalse();
    }
}
