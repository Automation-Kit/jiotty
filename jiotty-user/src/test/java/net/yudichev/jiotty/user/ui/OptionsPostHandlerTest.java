package net.yudichev.jiotty.user.ui;

import jakarta.annotation.Nullable;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionsPostHandlerTest {

    private ProgrammableClock clock;
    @Mock
    private OptionPersistence persistence;

    private OptionRegistryImpl registry;
    private OptionsPostHandler handler;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        registry = new OptionRegistryImpl(persistence);
        registry.start();
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("test");
        handler = new OptionsPostHandler(registry, executorProvider);
        handler.start();
        clock.tick();
    }

    @AfterEach
    void tearDown() {
        handler.stop();
        registry.stop();
        clock.tick();
    }

    @Test
    void missingNameParameterReturns400() {
        var responseBody = new StringWriter();
        var response = submit(null, null, responseBody);
        clock.tick();

        verify(response).setStatus(400);
        verify(response).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("Missing name parameter");
    }

    @Test
    void unknownOptionKeyReturns400() {
        var responseBody = new StringWriter();
        var response = submit("nonexistent", null, responseBody);
        clock.tick();

        verify(response).setStatus(400);
        verify(response).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("nonexistent");
    }

    @Test
    void onFormSubmitThrowingSynchronouslyReturns400(@Mock Option<?> option) {
        lenient().doReturn(OptionMeta.builder()
                                     .setFormOrder(0)
                                     .setTabName("tab")
                                     .setKey("throwing-opt")
                                     .setLabel("Throwing")
                                     .build())
                 .when(option).meta();
        lenient().when(option.toDto()).thenReturn(completedFuture(null));
        when(option.onFormSubmit(any())).thenThrow(new RuntimeException("boom"));
        registry.register(option);
        clock.tick();

        var responseBody = new StringWriter();
        var response = submit("throwing-opt", "val", responseBody);
        clock.tick();

        verify(response).setStatus(400);
        verify(response).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("boom");
    }

    @Test
    void asyncFailureReturns400(@Mock Option<?> option) {
        lenient().doReturn(OptionMeta.builder()
                                     .setFormOrder(0)
                                     .setTabName("tab")
                                     .setKey("async-fail")
                                     .setLabel("AsyncFail")
                                     .build())
                 .when(option).meta();
        lenient().when(option.toDto()).thenReturn(completedFuture(null));
        when(option.onFormSubmit(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("async boom")));
        registry.register(option);
        clock.tick();

        var responseBody = new StringWriter();
        var response = submit("async-fail", "val", responseBody);
        clock.tick();

        verify(response).setStatus(400);
        verify(response).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("async boom");
    }

    private HttpServletResponse submit(@Nullable String name, @Nullable String value, StringWriter responseBody) {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var asyncContext = mock(AsyncContext.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.startAsync()).thenReturn(asyncContext);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
        lenient().when(request.getParameter("name")).thenReturn(name);
        lenient().when(request.getParameter("value")).thenReturn(value);
        lenient().when(request.getParameterMap()).thenReturn(Map.of());
        asUnchecked(() -> when(response.getWriter()).thenReturn(new PrintWriter(responseBody)));

        handler.handle(request, response);
        return response;
    }

}
