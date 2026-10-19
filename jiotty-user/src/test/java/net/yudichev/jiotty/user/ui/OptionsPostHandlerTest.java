package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.DatabindException;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.user.ui.io.testing.DispatchedRequest;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.io.testing.FakeServletInputStream;
import net.yudichev.jiotty.user.ui.options.FormSubmitResult;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.OptionRejection;
import net.yudichev.jiotty.user.ui.options.OptionRejectionReasons;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CHARSET_UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.PAYLOAD_TOO_LARGE_413;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OptionsPostHandlerTest {
    /// A value shaped like the credentials an integration's form submits.
    private static final String CREDENTIAL = "hunter2-the-password";
    /// A percent escape whose two characters are not hex digits, which the form decoder refuses, quoting them.
    private static final String UNDECODABLE_ESCAPE = "%zq";
    private final List<DispatchedRequest> dispatchedRequests = new ArrayList<>(2);
    private ProgrammableClock clock;
    @Mock
    private OptionPersistence persistence;
    @Mock
    private AdminAlertService alertService;
    private OptionRegistryImpl registry;
    private OptionsPostHandler handler;
    private HttpServletRequest lastRequest;
    private FakeAsyncContext lastAsyncContext;

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

    /// Once the exchange has started, a deadline can complete it and the container recycle the request at any moment, so nothing reads it after dispatch.
    @AfterEach
    void tearDown() {
        handler.stop();
        registry.stop();
        clock.tick();
        assertThat(dispatchedRequests).allSatisfy(dispatchedRequest -> assertThat(dispatchedRequest.callsAfterDispatch()).isEmpty());
    }

    @Test
    void missingNameParameterReturns400() {
        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submit(null, null, responseOutput);
        clock.tick();

        assertRejection(response, responseOutput, OptionRejectionReasons.MISSING_OPTION_NAME);
    }

    @Test
    void unknownOptionKeyReturns400() {
        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submit("nonexistent", null, responseOutput);
        clock.tick();

        assertRejection(response, responseOutput, OptionRejectionReasons.UNKNOWN_OPTION);
        assertThat(responseOutput.output()).doesNotContain("nonexistent");
    }

    /// A fault on our side is not the user's value to correct: answering 400 would send them to fix a value that was never the problem, and nobody would be
    /// told the server broke.
    @Test
    void onFormSubmitThrowingSynchronouslyReportsAServerFault(@Mock Option<?> option) {
        register(option, "throwing-opt");
        when(option.onFormSubmit(any())).thenThrow(new RuntimeException("boom"));

        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submit("throwing-opt", "val", responseOutput);
        clock.tick();

        assertServerFault(response, responseOutput);
    }

    /// A submission the option never completes is answered with a server fault once the deadline passes, and alerted.
    @Test
    void aSubmissionThatNeverCompletesIsAnsweredByTheDeadline(@Mock Option<?> option) {
        register(option, "slow-opt");
        when(option.onFormSubmit(any())).thenReturn(new CompletableFuture<>());
        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submit("slow-opt", "val", responseOutput);
        clock.tick();

        lastAsyncContext.expireDeadlines();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        verify(alertService).raise(eq(WARNING), eq(OptionsPostHandler.TIMED_OUT_ALERT_TITLE), anyString());
    }

    @Test
    void asyncFailureReportsAServerFault(@Mock Option<?> option) {
        register(option, "async-fail");
        when(option.onFormSubmit(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("async boom")));

        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submit("async-fail", "val", responseOutput);
        clock.tick();

        assertServerFault(response, responseOutput);
    }

    /// An option value carries whatever the form held, so an exception that quotes it must not reach the client through the response.
    @Test
    void failureBodyCarriesNoExceptionText(@Mock Option<?> option) {
        register(option, "leaky-opt");
        when(option.onFormSubmit(any())).thenThrow(new IllegalArgumentException("cannot parse hunter2-the-password"));

        var responseOutput = new CapturingServletOutputStream();
        submit("leaky-opt", "hunter2-the-password", responseOutput);
        clock.tick();

        assertThat(responseOutput.output()).doesNotContain("hunter2-the-password", "cannot parse");
    }

    @Test
    void rejectionReasonAndParametersReachTheClient(@Mock Option<?> option) {
        register(option, "bounded-opt");
        doReturn(completedFuture(FormSubmitResult.rejected(OptionRejection.mustBeAtLeast(7)))).when(option).onFormSubmit(any());

        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submit("bounded-opt", "3", responseOutput);
        clock.tick();

        assertRejection(response, responseOutput, OptionRejectionReasons.MUST_BE_AT_LEAST);
        assertThat(Json.parse(responseOutput.output()).path("params").path("min").asInt()).isEqualTo(7);
    }

    /// A submission that cleared its option has no value to echo, so the response is 200 with the body `null`.
    @Test
    void aClearedOptionIsAnsweredWithJsonNull(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted(null))).when(option).onFormSubmit(any());

        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submit("opt", "", responseOutput);
        clock.tick();

        verify(response).setStatus(OK_200);
        assertThat(responseOutput.output()).isEqualTo("null");
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
    /// workspace/GDPR.md §7 records. The submission is read from the capped body alone, so the container's parameter parsing never runs.
    @Test
    void readsTheSubmissionFromTheBodyAlone(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted("normalised"))).when(option).onFormSubmit(any());

        submit("opt", "hunter2-the-password", new CapturingServletOutputStream());
        clock.tick();

        verify(option).onFormSubmit(Optional.of("hunter2-the-password"));
        verify(lastRequest, never()).getParameterMap();
        verify(lastRequest, never()).getParameter(any());
    }

    /// An option value is where an integration's credentials travel, so neither a value nor the escape a malformed one carries reaches carapp.log; the
    /// submission is logged by key and value length.
    @Test
    void aSubmittedValueNeverReachesTheLog(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted("normalised"))).when(option).onFormSubmit(any());

        try (LogCapture logCapture = LogCapture.capture(OptionsPostHandler.class, Level.DEBUG)) {
            submit("opt", CREDENTIAL, new CapturingServletOutputStream());
            submitBody(formOf("opt", CREDENTIAL + UNDECODABLE_ESCAPE), new CapturingServletOutputStream());
            clock.tick();

            assertThat(logCapture.lines()).flatMap(line -> line.text().toList())
                                          .anySatisfy(text -> assertThat(text).contains("valueLength=" + CREDENTIAL.length()))
                                          .noneSatisfy(text -> assertThat(text).containsAnyOf(CREDENTIAL, UNDECODABLE_ESCAPE.substring(1)));
        }
    }

    /// The value travels percent-encoded, so a character the form had to escape reaches the option as the user typed it.
    @Test
    void decodesAnEscapedValue(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted("normalised"))).when(option).onFormSubmit(any());

        submit("opt", "a&b=c ü", new CapturingServletOutputStream());
        clock.tick();

        verify(option).onFormSubmit(Optional.of("a&b=c ü"));
    }

    /// A form with no value clears its option, so [Option#onFormSubmit] is invoked with an empty value.
    @Test
    void aSubmissionWithNoValueReachesTheOptionAsAbsent(@Mock Option<?> option) {
        register(option, "opt");
        doReturn(completedFuture(FormSubmitResult.accepted(null))).when(option).onFormSubmit(any());

        submit("opt", null, new CapturingServletOutputStream());
        clock.tick();

        verify(option).onFormSubmit(Optional.empty());
    }

    @Test
    void anEmptyBodyIsRejectedForNamingNoOption() {
        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submitBody("", responseOutput);
        clock.tick();

        assertRejection(response, responseOutput, OptionRejectionReasons.MISSING_OPTION_NAME);
    }

    @Test
    void aBodyThatDoesNotDecodeIsRejectedAsInvalid() {
        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submitBody(formOf("opt", UNDECODABLE_ESCAPE), responseOutput);
        clock.tick();

        verify(response).setStatus(BAD_REQUEST_400);
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.INVALID_BODY);
        verifyNoInteractions(alertService);
    }

    /// The body is read only up to the cap, so a client cannot make the server hold whatever it chooses to send.
    @Test
    void aBodyPastTheCapIsRefusedAsTooLarge() {
        var responseOutput = new CapturingServletOutputStream();
        HttpServletResponse response = submitBody(formOf("opt", "v".repeat(OptionsPostHandler.MAX_BODY_BYTES)), responseOutput);
        clock.tick();

        verify(response).setStatus(PAYLOAD_TOO_LARGE_413);
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.BODY_TOO_LARGE);
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

        verify(accepted).setCharacterEncoding(CHARSET_UTF_8);
        verify(rejected).setCharacterEncoding(CHARSET_UTF_8);
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

    private static void assertRejection(HttpServletResponse response, CapturingServletOutputStream responseOutput, String expectedReason) {
        verify(response).setStatus(BAD_REQUEST_400);
        verify(response).setContentType(CONTENT_TYPE_JSON);
        assertThat(Json.parse(responseOutput.output()).path("reason").asText()).isEqualTo(expectedReason);
    }

    /// 500 and an alert, so the fault reaches an operator rather than being dressed up as a value the user got wrong.
    private void assertServerFault(HttpServletResponse response, CapturingServletOutputStream responseOutput) {
        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.INTERNAL_ERROR);
        verify(alertService).raise(eq(WARNING), any(), any(), any(Throwable.class));
    }

    /// A client that hung up surfaces as the response's writes failing, which is what the container does once the connection is gone.
    private void submitToADepartedClient(String name, String value) {
        var brokenPipe = new CapturingServletOutputStream();
        brokenPipe.failWrites(true);
        submit(name, value, brokenPipe);
    }

    private HttpServletResponse submit(@Nullable String name, @Nullable String value, CapturingServletOutputStream responseOutput) {
        var form = new StringBuilder(64);
        if (name != null) {
            form.append(OptionsPostHandler.NAME_FIELD).append('=').append(URLEncoder.encode(name, UTF_8));
        }
        if (value != null) {
            form.append(form.isEmpty() ? "" : "&").append(OptionsPostHandler.VALUE_FIELD).append('=').append(URLEncoder.encode(value, UTF_8));
        }
        return submitBody(form.toString(), responseOutput);
    }

    /// Creates a form naming `name` whose value field holds `rawValue` as written, escapes included.
    private static String formOf(String name, String rawValue) {
        return OptionsPostHandler.NAME_FIELD + '=' + name + '&' + OptionsPostHandler.VALUE_FIELD + '=' + rawValue;
    }

    /// Submits `form` as the request body, exactly as the app posts it: `application/x-www-form-urlencoded`.
    private HttpServletResponse submitBody(String form, CapturingServletOutputStream responseOutput) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        lastRequest = request;
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        var asyncContext = new FakeAsyncContext(request, response);
        lastAsyncContext = asyncContext;
        when(request.startAsync()).thenReturn(asyncContext);
        when(request.getContentLengthLong()).thenReturn((long) form.length());
        asUnchecked(() -> lenient().when(request.getInputStream()).thenReturn(FakeServletInputStream.of(form)));
        // The handler streams into the output stream, not the writer, so the body is captured as the bytes Jackson emitted.
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(responseOutput));

        dispatchedRequests.add(DispatchedRequest.dispatch(request, asyncContext, dispatched -> handler.handle(dispatched, response)));
        return response;
    }
}
