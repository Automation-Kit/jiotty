package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.inject.Provider;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetDisplayablesListHandlerTest {

    private ProgrammableClock clock;
    private DisplayableRegistryImpl registry;
    private GetDisplayablesListHandler handler;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("test");
        registry = new DisplayableRegistryImpl(executorProvider);
        registry.start();
        clock.tick();
        handler = new GetDisplayablesListHandler(registry);
        when(request.getPathInfo()).thenReturn("/displayables");
        when(request.getMethod()).thenReturn("GET");
    }

    @AfterEach
    void tearDown() {
        registry.stop();
        clock.tick();
    }

    @Test
    void returnsVisibleDisplayables() throws IOException {
        registry.register(createDisplayable("d1", "Display 1"));
        registry.register(createDisplayable("d2", "Display 2"));
        clock.tick();

        var writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);

        Map<String, Object> parsed = parseJson(writer.toString());
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) parsed.get("items");
        assertThat(items).hasSize(2);
        assertThat(items).extracting(m -> m.get("id")).containsExactly("d1", "d2");
        assertThat(items).extracting(m -> m.get("name")).containsExactly("Display 1", "Display 2");
    }

    @Test
    void excludesNonVisibleDisplayables() throws IOException {
        registry.register(createDisplayable("d1", "Visible"));
        var hidden = createDisplayable("d2", "Hidden");
        when(hidden.visible()).thenReturn(false);
        registry.register(hidden);
        clock.tick();

        var writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);

        Map<String, Object> parsed = parseJson(writer.toString());
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) parsed.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().get("id")).isEqualTo("d1");
    }

    @Test
    void returnsEmptyItemsWhenNoDisplayables() throws IOException {
        var writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);

        Map<String, Object> parsed = parseJson(writer.toString());
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) parsed.get("items");
        assertThat(items).isEmpty();
    }

    @Test
    void sanitizesIdToSafeId() throws IOException {
        registry.register(createDisplayable("my display!", "Display"));
        clock.tick();

        var writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        handler.handle(request, response);

        Map<String, Object> parsed = parseJson(writer.toString());
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) parsed.get("items");
        assertThat(items.getFirst().get("safeId")).isEqualTo("my-display-");
    }

    private static Displayable createDisplayable(String id, String displayName) {
        var displayable = Mockito.mock(Displayable.class);
        when(displayable.getId()).thenReturn(id);
        lenient().when(displayable.getDisplayName()).thenReturn(displayName);
        lenient().when(displayable.supportsData()).thenReturn(false);
        lenient().when(displayable.visible()).thenReturn(true);
        lenient().when(displayable.toDto()).thenReturn(CompletableFuture.completedFuture(null));
        return displayable;
    }

    private static Map<String, Object> parseJson(String json) {
        return getAsUnchecked(() -> UIJson.MAPPER.readValue(json, new TypeReference<>() {}));
    }
}
