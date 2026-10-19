package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertSeverity;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.io.testing.DispatchedRequest;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetDisplayableItemHandlerTest {
    private final CapturingServletOutputStream responseOutput = new CapturingServletOutputStream();
    private final List<DispatchedRequest> dispatchedRequests = new ArrayList<>(1);
    private ProgrammableClock clock;
    private DisplayableRegistryImpl registry;
    private TestAdminAlertService alertService;
    private GetDisplayableItemHandler handler;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    private FakeAsyncContext asyncContext;

    @BeforeEach
    void setUp() {
        asyncContext = new FakeAsyncContext(request, response);
        clock = new ProgrammableClock();
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("test");
        registry = new DisplayableRegistryImpl(executorProvider);
        registry.start();
        alertService = new TestAdminAlertService();
        handler = new GetDisplayableItemHandler(registry, alertService, executorProvider, "user-1");
        handler.start();
        clock.tick();
        lenient().when(request.getPathInfo()).thenReturn(GetDisplayableItemHandler.PATH);
        lenient().when(request.getMethod()).thenReturn("GET");
        lenient().when(request.startAsync()).thenReturn(asyncContext);
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(responseOutput));
    }

    /// Once the exchange has started, a deadline can complete it and the container recycle the request at any moment, so nothing reads it after dispatch.
    @AfterEach
    void tearDown() {
        handler.stop();
        registry.stop();
        clock.tick();
        assertThat(dispatchedRequests).allSatisfy(dispatchedRequest -> assertThat(dispatchedRequest.callsAfterDispatch()).isEmpty());
    }

    @Test
    void returnsDto() {
        registry.register(createDisplayable("d1", "Display 1", completedFuture(new HistoryDisplayableDto(ImmutableMap.of("key", ImmutableList.of())))));
        clock.tick();
        when(request.getParameter(GetDisplayableItemHandler.ID_PARAMETER)).thenReturn("d1");

        dispatch();
        clock.tick();

        assertThat(asyncContext.isCompleted()).isTrue();
        JsonNode responseJson = Json.parse(responseOutput.output());
        assertThat(responseJson.path("id").asText()).isEqualTo("d1");
        assertThat(responseJson.has("dto")).isTrue();
    }

    /// A DTO that never arrives is answered with a server fault once the deadline passes, and alerted.
    @Test
    void aDtoThatNeverArrivesIsAnsweredByTheDeadline() {
        registry.register(createDisplayable("d1", "Display 1", new CompletableFuture<>()));
        clock.tick();
        when(request.getParameter(GetDisplayableItemHandler.ID_PARAMETER)).thenReturn("d1");
        dispatch();
        clock.tick();

        asyncContext.expireDeadlines();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(GetDisplayableItemHandler.TIMED_OUT_ALERT_TITLE));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "null, " + BAD_REQUEST_400 + ", " + ErrorResponse.MISSING_ID,
            "'   ', " + BAD_REQUEST_400 + ", " + ErrorResponse.MISSING_ID,
            "nonexistent, " + NOT_FOUND_404 + ", " + ErrorResponse.UNKNOWN_ID
    }, nullValues = "null")
    void returnsErrorForInvalidId(String id, int expectedStatus, String expectedError) {
        when(request.getParameter(GetDisplayableItemHandler.ID_PARAMETER)).thenReturn(id);

        dispatch();

        verify(response).setStatus(expectedStatus);
        assertThat(responseOutput.output()).contains(expectedError);
    }

    @Test
    void returns500WhenDtoFails() {
        registry.register(createDisplayable("d1", "Display 1",
                                            CompletableFuture.failedFuture(new RuntimeException("DTO generation failed"))));
        clock.tick();
        when(request.getParameter(GetDisplayableItemHandler.ID_PARAMETER)).thenReturn("d1");

        dispatch();
        clock.tick();

        assertThat(asyncContext.isCompleted()).isTrue();
        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        // The 500 body is a fixed opaque code, so the exception text ("DTO generation failed") never reaches the client.
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.INTERNAL_ERROR);
        assertRaisedAlert(GetDisplayableItemHandler.DTO_FAILED_ALERT_TITLE);
    }

    /// A DTO that fails to serialise is a server defect, so it raises a WARNING admin alert and the request is answered with a bare 500.
    @Test
    void alertsWhenTheDtoCannotBeSerialised() {
        registry.register(createDisplayable("d1", "Display 1", completedFuture(new UnserialisableDto("value"))));
        clock.tick();
        when(request.getParameter(GetDisplayableItemHandler.ID_PARAMETER)).thenReturn("d1");

        dispatch();
        clock.tick();

        assertThat(asyncContext.isCompleted()).isTrue();
        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertRaisedAlert(GetDisplayableItemHandler.ENCODING_FAILED_ALERT_TITLE);
    }

    private void dispatch() {
        dispatchedRequests.add(DispatchedRequest.dispatch(request, asyncContext, dispatched -> handler.handle(dispatched, response)));
    }

    private void assertRaisedAlert(String title) {
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.WARNING);
                    assertThat(alert.title()).isEqualTo(title);
                });
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

    /// A DTO with a property that throws when read, standing in for a mis-mapped DTO that fails to serialise.
    private record UnserialisableDto(String value) implements DisplayableDto {
        // Jackson calls it reflectively when it serialises the DTO, which is the failure under test.
        @SuppressWarnings("unused")
        @Override
        public String value() {
            throw new IllegalStateException("not readable");
        }

        @Override
        public String type() {
            return "unserialisable";
        }
    }
}
