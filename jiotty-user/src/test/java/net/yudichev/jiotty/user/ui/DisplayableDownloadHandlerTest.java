package net.yudichev.jiotty.user.ui;

import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisplayableDownloadHandlerTest {

    private ProgrammableClock clock;
    private DisplayableRegistryImpl registry;
    private DisplayableDownloadHandler handler;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private AsyncContext asyncContext;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("test");
        registry = new DisplayableRegistryImpl(executorProvider);
        registry.start();
        handler = new DisplayableDownloadHandler(registry, executorProvider);
        handler.start();
        clock.tick();
        lenient().when(request.getPathInfo()).thenReturn("/displayables/download");
        lenient().when(request.getMethod()).thenReturn("GET");
    }

    @AfterEach
    void tearDown() {
        handler.stop();
        registry.stop();
        clock.tick();
    }

    @Test
    void unknownDisplayableIdReturns404() throws IOException {
        var writer = new StringWriter();
        when(request.startAsync()).thenReturn(asyncContext);
        runOnAsyncStart();
        when(request.getParameter("displayableId")).thenReturn("nonexistent");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);
        clock.tick();

        verify(response).setStatus(404);
        assertThat(writer.toString()).contains("No displayable found").contains("nonexistent");
        verify(asyncContext).complete();
    }

    @Test
    void missingDownloadIdReturns404() throws IOException {
        registerDisplayable("d1");
        clock.tick();
        var writer = new StringWriter();
        when(request.startAsync()).thenReturn(asyncContext);
        runOnAsyncStart();
        when(request.getParameter("displayableId")).thenReturn("d1");
        when(request.getParameter("downloadId")).thenReturn(null);
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);
        clock.tick();

        verify(response).setStatus(404);
        assertThat(writer.toString()).contains("Missing 'downloadId' parameter");
        verify(asyncContext).complete();
    }

    @Test
    void delegatesToDisplayableHandleDownloadAndCompletes() throws IOException {
        var displayable = registerDisplayable("d1");
        when(displayable.handleDownload(eq("dl-1"), eq(response))).thenReturn(completedFuture(null));
        clock.tick();

        when(request.startAsync()).thenReturn(asyncContext);
        runOnAsyncStart();
        when(request.getParameter("displayableId")).thenReturn("d1");
        when(request.getParameter("downloadId")).thenReturn("dl-1");

        handler.handle(request, response);
        clock.tick();

        verify(displayable).handleDownload("dl-1", response);
        verify(asyncContext).complete();
    }

    @Test
    void downloadFailureWritesErrorAndReturns400() throws IOException {
        var displayable = registerDisplayable("d1");
        when(displayable.handleDownload(eq("dl-1"), eq(response)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("download exploded")));
        clock.tick();

        var writer = new StringWriter();
        when(request.startAsync()).thenReturn(asyncContext);
        runOnAsyncStart();
        when(request.getParameter("displayableId")).thenReturn("d1");
        when(request.getParameter("downloadId")).thenReturn("dl-1");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);
        clock.tick();

        verify(response).setStatus(400);
        assertThat(writer.toString()).contains("download exploded");
        verify(asyncContext).complete();
    }

    @Test
    void wrongPathReturnsUnknownPath() throws IOException {
        var writer = new StringWriter();
        when(request.getPathInfo()).thenReturn("/displayables/download/something");
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);

        verify(response).setStatus(404);
    }

    @Test
    void wrongMethodReturns405() {
        when(request.getMethod()).thenReturn("POST");

        handler.handle(request, response);

        verify(response).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    private Displayable registerDisplayable(String id) {
        var displayable = Mockito.mock(Displayable.class);
        when(displayable.getId()).thenReturn(id);
        lenient().when(displayable.getDisplayName()).thenReturn(id);
        lenient().when(displayable.supportsData()).thenReturn(false);
        lenient().when(displayable.visible()).thenReturn(true);
        registry.register(displayable);
        return displayable;
    }

    private void runOnAsyncStart() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
    }
}
