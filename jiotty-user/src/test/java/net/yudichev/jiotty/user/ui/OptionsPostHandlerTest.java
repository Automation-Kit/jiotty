package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.DatabindException;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.options.FormSubmitResult;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.OptionRejection;
import net.yudichev.jiotty.user.ui.options.OptionRejectionReasons;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionsPostHandlerTest {

    private ProgrammableClock clock;
    @Mock
    private OptionPersistence persistence;
    @Mock
    private AdminAlertService alertService;

    private OptionRegistryImpl registry;
    private OptionsPostHandler handler;
    private HttpServletRequest lastRequest;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        registry = new OptionRegistryImpl(persistence);
        registry.start();
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("test");
        handler = new OptionsPostHandler(registry, alertService, executorProvider, "user-1");
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
        var responseBody = new CapturingServletOutputStream();
        HttpServletResponse response = submit(null, null, responseBody);
        clock.tick();

        assertRejection(response, responseBody, OptionRejectionReasons.MISSING_OPTION_NAME);
    }

    @Test
    void unknownOptionKeyReturns400() {
        var responseBody = new CapturingServletOutputStream();
        HttpServletResponse response = submit("nonexistent", null, responseBody);
        clock.tick();

        assertRejection(response, responseBody, OptionRejectionReasons.UNKNOWN_OPTION);
        assertThat(responseBody.output()).doesNotContain("nonexistent");
    }

    /// A fault on our side is not the user's value to correct: answering 400 would send them to fix a value that was never the problem, and nobody would be
    /// told the server broke.
    @Test
    void onFormSubmitThrowingSynchronouslyReportsAServerFault(@Mock Option<?> option) {
        register(option, "throwing-opt");
        when(option.onFormSubmit(any())).thenThrow(new RuntimeException("boom"));

        var responseBody = new CapturingServletOutputStream();
        HttpServletResponse response = submit("throwing-opt", "val", responseBody);
        clock.tick();

        assertServerFault(response, responseBody);
    }

    @Test
    void asyncFailureReportsAServerFault(@Mock Option<?> option) {
        register(option, "async-fail");
        when(option.onFormSubmit(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("async boom")));

        var responseBody = new CapturingServletOutputStream();
        HttpServletResponse response = submit("async-fail", "val", responseBody);
        clock.tick();

        assertServerFault(response, responseBody);
    }

    /// An option value carries whatever the form held, so an exception that quotes it must not reach the client through the response.
    @Test
    void failureBodyCarriesNoExceptionText(@Mock Option<?> option) {
        register(option, "leaky-opt");
        when(option.onFormSubmit(any())).thenThrow(new IllegalArgumentException("cannot parse hunter2-the-password"));

        var responseBody = new CapturingServletOutputStream();
        submit("leaky-opt", "hunter2-the-password", responseBody);
        clock.tick();

        assertThat(responseBody.output()).doesNotContain("hunter2-the-password", "cannot parse");
    }

    @Test
    void rejectionReasonAndParametersReachTheClient(@Mock Option<?> option) {
        register(option, "bounded-opt");
        doReturn(completedFuture(FormSubmitResult.rejected(OptionRejection.mustBeAtLeast(7)))).when(option).onFormSubmit(any());

        var responseBody = new CapturingServletOutputStream();
        HttpServletResponse response = submit("bounded-opt", "3", responseBody);
        clock.tick();

        assertRejection(response, responseBody, OptionRejectionReasons.MUST_BE_AT_LEAST);
        assertThat(Json.parse(responseBody.output()).path("params").path("min").asInt()).isEqualTo(7);
    }

    /// A value the option refused is an ordinary outcome of a form someone filled in, so it must not page an operator — only the server's own faults do.
    @Test
    void aRejectedValueRaisesNoAlert(@Mock Option<?> option) {
        register(option, "bounded-opt");
        doReturn(completedFuture(FormSubmitResult.rejected(OptionRejection.mustBeAtMost(4)))).when(option).onFormSubmit(any());

        submit("bounded-opt", "9", new CapturingServletOutputStream());
        clock.tick();

        verifyNoInteractions(alertService);
    }

    /// An option value is where an integration's credentials travel, and reading the parameter map would put the whole of it in carapp.log — the exposure
    /// workspace/GDPR.md §7 records. Only the two named parameters are ever read.
    @Test
    void neverReadsTheWholeParameterMap(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted("normalised"))).when(option).onFormSubmit(any());

        submit("opt", "hunter2-the-password", new CapturingServletOutputStream());
        clock.tick();

        verify(lastRequest, never()).getParameterMap();
        verify(lastRequest).getParameter("value");
    }

    /// Jackson writes UTF-8 into the stream whatever the platform default is, so the header is the only thing telling the client that. Both paths state it.
    @Test
    void bothAnswersDeclareUtf8(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted("normalised"))).when(option).onFormSubmit(any());

        HttpServletResponse accepted = submit("opt", "v", new CapturingServletOutputStream());
        clock.tick();
        HttpServletResponse rejected = submit("nonexistent", "v", new CapturingServletOutputStream());
        clock.tick();

        verify(accepted).setCharacterEncoding("utf-8");
        verify(rejected).setCharacterEncoding("utf-8");
    }

    /// A client that hangs up mid-response is ordinary traffic, so it must not page an operator.
    @Test
    void clientGoingAwayRaisesNoAlert(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted("normalised"))).when(option).onFormSubmit(any());

        submitToADepartedClient("opt", "v");
        clock.tick();

        verifyNoInteractions(alertService);
    }

    /// The other arm: a response of ours that will not serialise is nobody's to retry, so it alerts — and carries the failure that actually occurred, which is
    /// the only thing naming the response that could not be written.
    @Test
    void anUnserialisableResponseAlertsWithTheFailure(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted(new Object()))).when(option).onFormSubmit(any());

        submit("opt", "v", new CapturingServletOutputStream());
        clock.tick();

        verify(alertService).raise(eq(WARNING), any(), any(), isA(DatabindException.class));
    }

    private void register(Option<?> option, String key) {
        lenient().doReturn(OptionMeta.builder()
                                     .setFormOrder(0)
                                     .setTabName("tab")
                                     .setKey(key)
                                     .setLabel(key)
                                     .build())
                 .when(option).meta();
        lenient().when(option.toDto()).thenReturn(completedFuture(null));
        registry.register(option);
        clock.tick();
    }

    private static void assertRejection(HttpServletResponse response, CapturingServletOutputStream responseBody, String expectedReason) {
        verify(response).setStatus(BAD_REQUEST_400);
        verify(response).setContentType(CONTENT_TYPE_JSON);
        assertThat(Json.parse(responseBody.output()).path("reason").asText()).isEqualTo(expectedReason);
    }

    /// 500 and an alert, so the fault reaches an operator rather than being dressed up as a value the user got wrong.
    private void assertServerFault(HttpServletResponse response, CapturingServletOutputStream responseBody) {
        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(Json.parse(responseBody.output()).path("error").asText()).isEqualTo("INTERNAL_ERROR");
        verify(alertService).raise(eq(WARNING), any(), any(), any(Throwable.class));
    }

    /// A client that hung up surfaces as the response's writes failing, which is what the container does once the connection is gone.
    private void submitToADepartedClient(String name, String value) {
        var brokenPipe = new CapturingServletOutputStream();
        brokenPipe.failWrites(true);
        submit(name, value, brokenPipe);
    }

    private HttpServletResponse submit(@Nullable String name, @Nullable String value, CapturingServletOutputStream responseBody) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lastRequest = request;
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext asyncContext = mock(AsyncContext.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.startAsync()).thenReturn(asyncContext);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
        lenient().when(request.getParameter("name")).thenReturn(name);
        lenient().when(request.getParameter("value")).thenReturn(value);
        // The handler streams into the output stream, not the writer, so the body is captured as the bytes Jackson emitted.
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(responseBody));

        handler.handle(request, response);
        return response;
    }
}
