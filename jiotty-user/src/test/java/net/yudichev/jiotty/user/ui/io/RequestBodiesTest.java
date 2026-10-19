package net.yudichev.jiotty.user.ui.io;

import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.io.testing.FailingServletInputStream;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.io.testing.FakeServletInputStream;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestBodiesTest {
    private static final ObjectReader READER = Json.createReaderFor(Note.class);
    private static final int MAX_BYTES = 64 * 1024;
    private final CapturingServletOutputStream responseStream = new CapturingServletOutputStream();
    private final List<Deadline.Stall> expiries = new ArrayList<>();
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    private FakeAsyncContext asyncContext;
    private AsyncExchange exchange;

    @BeforeEach
    void setUp() {
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(responseStream));
        asyncContext = new FakeAsyncContext(request, response);
        when(request.startAsync()).thenReturn(asyncContext);
        Deadline deadline = Deadline.after(Duration.ofSeconds(5), Answer.of(SERVICE_UNAVAILABLE_503), _ -> {}, expiries::add);
        exchange = AsyncExchange.start(new TestAdminAlertService(), request, response, deadline);
    }

    @AfterEach
    void tearDown() {
        assertThat(responseStream.leakedBuffers()).as("every chunk the body was read into returned to the pool").isEmpty();
    }

    @Test
    void parsesABodyThatFitsOneChunk() {
        withBody("{\"text\":\"hello\"}");

        assertThat(read(MAX_BYTES)).isEqualTo(new ReceivedBody.Parsed<>(new Note("hello")));
    }

    @Test
    void parsesABodySpreadOverSeveralChunks() {
        String text = "z".repeat(PooledChunkOutputStream.CHUNK_SIZE * 2);
        withBody("{\"text\":\"" + text + "\"}");

        assertThat(read(MAX_BYTES)).isEqualTo(new ReceivedBody.Parsed<>(new Note(text)));
    }

    @Test
    void reportsAnEmptyBody() {
        withBody("");

        assertThat(read(MAX_BYTES)).isSameAs(ReceivedBody.Empty.instance());
    }

    @Test
    void reportsABodyLongerThanTheLimitWithoutParsingIt() {
        withBody("{\"text\":\"" + "q".repeat(100) + "\"}");

        assertThat(read(50)).isSameAs(ReceivedBody.TooLarge.instance());
    }

    @Test
    void acceptsABodyOfExactlyTheLimit() {
        String body = "{\"text\":\"exact\"}";
        withBody(body);

        assertThat(read(body.length())).isEqualTo(new ReceivedBody.Parsed<>(new Note("exact")));
    }

    @Test
    void refusesABodyOneByteOverTheLimit() {
        String body = "{\"text\":\"exact\"}";
        withBody(body);

        assertThat(read(body.length() - 1)).isSameAs(ReceivedBody.TooLarge.instance());
    }

    /// The read stops once the limit is passed, so a client cannot make the server buffer whatever it chooses to send.
    @Test
    void leavesTheRestOfALongBodyUnreadOnceTheLimitIsPassed() {
        String body = "{\"text\":\"" + "q".repeat(PooledChunkOutputStream.CHUNK_SIZE * 4) + "\"}";
        FakeServletInputStream requestBody = FakeServletInputStream.of(body);
        withInputStream(requestBody);

        assertThat(read(10)).isSameAs(ReceivedBody.TooLarge.instance());
        assertThat(requestBody.available()).isGreaterThan(PooledChunkOutputStream.CHUNK_SIZE * 2);
    }

    @Test
    void reportsABodyThatDoesNotParseWithTheParseFailure() {
        withBody("not json");

        assertThat(read(MAX_BYTES)).isInstanceOfSatisfying(ReceivedBody.Malformed.class, malformed -> assertThat(malformed.cause()).isNotNull());
    }

    @Test
    void reportsAJsonNullAsMalformed() {
        withBody("null");

        assertThat(read(MAX_BYTES)).isInstanceOf(ReceivedBody.Malformed.class);
    }

    @Test
    void aBodyDeclaredEmptyIsReportedEmptyWithoutBeingRead() {
        when(request.getContentLengthLong()).thenReturn(0L);

        assertThat(read(MAX_BYTES)).isSameAs(ReceivedBody.Empty.instance());
        asUnchecked(() -> verify(request, never()).getInputStream());
    }

    /// A body received before its type is known can be parsed with a reader for that type, repeatedly, until released.
    @Test
    void aReceivedBodyParsesOnceItsTypeIsKnown() {
        withBody("{\"text\":\"later\"}");

        UnparsedBody body = RequestBodies.receive(exchange, MAX_BYTES).getNow(null);

        assertThat(body.<Note>parseAs(READER)).isEqualTo(new ReceivedBody.Parsed<>(new Note("later")));
        assertThat(body.<Note>parseAs(READER)).isEqualTo(new ReceivedBody.Parsed<>(new Note("later")));
        body.release();
        body.release();
    }

    @Test
    void aReceivedBodyLongerThanTheLimitParsesAsTooLarge() {
        withBody("{\"text\":\"" + "q".repeat(100) + "\"}");

        UnparsedBody body = RequestBodies.receive(exchange, 50).getNow(null);

        assertThat(body.<Note>parseAs(READER)).isSameAs(ReceivedBody.TooLarge.instance());
        body.release();
    }

    @Test
    void failsWhenTheBodyCannotBeRead() {
        var failure = new IOException("connection reset");
        withInputStream(new FailingServletInputStream(failure));

        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, MAX_BYTES, READER);

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withCause(failure);
    }

    @Test
    void failsWhenTheBodyCannotBeOpened() {
        var failure = new IOException("connection reset");
        lenient().when(request.getContentLengthLong()).thenReturn(-1L);
        asUnchecked(() -> when(request.getInputStream()).thenThrow(failure));

        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, MAX_BYTES, READER);

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withCause(failure);
    }

    /// A body arriving in several parts is read across successive [ReadListener#onDataAvailable()] callbacks, and the read ends only at
    /// [ReadListener#onAllDataRead()].
    @Test
    void readsABodyThatArrivesInParts() {
        PartialServletInputStream body = withPartialInputStream();
        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, MAX_BYTES, READER);

        body.arrive("{\"text\":");
        assertThat(result).isNotDone();
        body.arrive("\"in parts\"}");
        body.end();

        assertThat(result.getNow(null)).isEqualTo(new ReceivedBody.Parsed<>(new Note("in parts")));
    }

    /// Once the body exceeds the limit the outcome is TooLarge; a later read failure leaves it unchanged and returns no chunk to the pool twice.
    @Test
    void aFailureAfterTheLimitWasPassedLeavesTheBodyTooLarge() {
        PartialServletInputStream body = withPartialInputStream();
        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, 5, READER);
        body.arrive("{\"text\":\"far too long\"}");

        body.fail(new IOException("connection reset"));

        assertThat(result.getNow(null)).isSameAs(ReceivedBody.TooLarge.instance());
    }

    /// The container can complete an exchange whose body is still arriving, as on a timeout, and invokes no read callback when it does; the read fails, and
    /// every chunk it borrowed returns to the pool.
    @Test
    void aReadEndsWhenItsExchangeCompletesFirst() {
        PartialServletInputStream body = withPartialInputStream();
        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, MAX_BYTES, READER);
        body.arrive("{\"text\":");

        asyncContext.complete();

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withCauseInstanceOf(IOException.class);
    }

    /// The container aborts an exchange whose client has gone, and invokes no read callback for it.
    @Test
    void aReadEndsWhenItsExchangeIsAborted() {
        withPartialInputStream();
        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, MAX_BYTES, READER);

        asyncContext.abort();

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withCauseInstanceOf(IOException.class);
    }

    @Test
    void aDeadlineExpiringWhileTheBodyArrivesFindsTheExchangeReceivingIt() {
        withPartialInputStream();
        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, MAX_BYTES, READER);

        asyncContext.expireDeadlines();

        assertThat(expiries).containsExactly(Deadline.Stall.RECEIVING_BODY);
        assertThat(result).as("the deadline's answer ends the exchange, and the read with it").isCompletedExceptionally();
    }

    /// The body counts as received before what the caller chains on the read runs, so a caller stalling from there stalls as handling the request.
    @Test
    void theBodyCountsAsReceivedBeforeTheCallersContinuationRuns() {
        withBody("{\"text\":\"done\"}");

        RequestBodies.read(exchange, MAX_BYTES, READER).thenRun(asyncContext::expireDeadlines);

        assertThat(expiries).containsExactly(Deadline.Stall.HANDLING);
    }

    /// The exchange completing after the read has ended leaves the read as it ended.
    @Test
    void anExchangeCompletingAfterTheReadLeavesItsOutcome() {
        withBody("{\"text\":\"done\"}");
        CompletableFuture<ReceivedBody<Note>> result = RequestBodies.read(exchange, MAX_BYTES, READER);

        asyncContext.complete();

        assertThat(result.getNow(null)).isEqualTo(new ReceivedBody.Parsed<>(new Note("done")));
    }

    @Test
    void refusesANonPositiveLimit() {
        assertThatThrownBy(() -> RequestBodies.read(exchange, 0, READER)).isInstanceOf(IllegalArgumentException.class);
    }

    private void withBody(String body) {
        withInputStream(FakeServletInputStream.of(body));
    }

    private PartialServletInputStream withPartialInputStream() {
        var inputStream = new PartialServletInputStream();
        withInputStream(inputStream);
        return inputStream;
    }

    /// Serves `inputStream` as a body of unstated length, as a chunked upload sends, so the read never takes the declared-empty short cut.
    private void withInputStream(ServletInputStream inputStream) {
        lenient().when(request.getContentLengthLong()).thenReturn(-1L);
        asUnchecked(() -> when(request.getInputStream()).thenReturn(inputStream));
    }

    private ReceivedBody<Note> read(int maxBytes) {
        return RequestBodies.<Note>read(exchange, maxBytes, READER).getNow(null);
    }

    private record Note(String text) {}

    /// A [ServletInputStream] fed one part at a time, invoking the read listener as a container does: ready while a part is unread, not ready once it is.
    private static final class PartialServletInputStream extends ServletInputStream {
        private ByteArrayInputStream part = new ByteArrayInputStream(new byte[0]);
        private boolean ended;
        /// `null` until the reader registers itself.
        private @Nullable ReadListener listener;

        void arrive(String text) {
            part = new ByteArrayInputStream(text.getBytes(UTF_8));
            asUnchecked(() -> listener().onDataAvailable());
        }

        void end() {
            ended = true;
            asUnchecked(() -> listener().onAllDataRead());
        }

        void fail(Throwable failure) {
            listener().onError(failure);
        }

        @Override
        public int read() {
            return part.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return part.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return ended && part.available() == 0;
        }

        @Override
        public boolean isReady() {
            return part.available() > 0;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            listener = readListener;
        }

        private ReadListener listener() {
            assert listener != null : "a read listener is registered before the body arrives";
            return listener;
        }
    }
}
