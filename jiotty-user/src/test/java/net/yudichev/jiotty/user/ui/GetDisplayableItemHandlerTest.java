package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetDisplayableItemHandlerTest {

    private ProgrammableClock clock;
    private DisplayableRegistryImpl registry;
    private GetDisplayableItemHandler handler;

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
        handler = new GetDisplayableItemHandler(registry, executorProvider);
        handler.start();
        clock.tick();
        lenient().when(request.getPathInfo()).thenReturn("/displayables/item");
        lenient().when(request.getMethod()).thenReturn("GET");
    }

    @AfterEach
    void tearDown() {
        handler.stop();
        registry.stop();
        clock.tick();
    }

    @Test
    void returnsDto() throws IOException {
        var dto = new HistoryDisplayableDto(Map.of("key", List.of()));
        registry.register(createDisplayable("d1", "Display 1", completedFuture(dto)));
        clock.tick();

        var writer = new StringWriter();
        when(request.getParameter("id")).thenReturn("d1");
        when(request.startAsync()).thenReturn(asyncContext);
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);
        clock.tick();

        verify(asyncContext).complete();
        Map<String, Object> parsed = parseJson(writer.toString());
        assertThat(parsed.get("id")).isEqualTo("d1");
        assertThat(parsed).containsKey("dto");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "null, 400, missing id",
            "'   ', 400, missing id",
            "nonexistent, 404, unknown id"
    }, nullValues = "null")
    void returnsErrorForInvalidId(String id, int expectedStatus, String expectedError) throws IOException {
        var writer = new StringWriter();
        when(request.getParameter("id")).thenReturn(id);
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);

        verify(response).setStatus(expectedStatus);
        assertThat(writer.toString()).contains(expectedError);
    }

    @Test
    void returns500WhenDtoFails() throws IOException {
        registry.register(createDisplayable("d1", "Display 1",
                                            CompletableFuture.failedFuture(new RuntimeException("DTO generation failed"))));
        clock.tick();

        var writer = new StringWriter();
        when(request.getParameter("id")).thenReturn("d1");
        when(request.startAsync()).thenReturn(asyncContext);
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);
        clock.tick();

        verify(asyncContext).complete();
        verify(response).setStatus(500);
        Map<String, Object> parsed = parseJson(writer.toString());
        assertThat((String) parsed.get("error")).contains("DTO generation failed");
    }

    private static Displayable createDisplayable(String id, String displayName, CompletableFuture<DisplayableDto> dto) {
        var displayable = Mockito.mock(Displayable.class);
        when(displayable.getId()).thenReturn(id);
        lenient().when(displayable.getDisplayName()).thenReturn(displayName);
        lenient().when(displayable.supportsData()).thenReturn(false);
        lenient().when(displayable.visible()).thenReturn(true);
        when(displayable.toDto()).thenReturn(dto);
        return displayable;
    }

    private static Map<String, Object> parseJson(String json) {
        return getAsUnchecked(() -> UIJson.MAPPER.readValue(json, new TypeReference<>() {}));
    }
}
