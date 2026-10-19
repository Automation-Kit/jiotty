package net.yudichev.jiotty.user.ui.sse;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static net.yudichev.jiotty.user.ui.io.testing.ResponseBodies.encodeAll;
import static net.yudichev.jiotty.user.ui.sse.PendingFrames.Offer.ACCEPTED;
import static net.yudichev.jiotty.user.ui.sse.PendingFrames.Offer.OVERFLOWED;
import static net.yudichev.jiotty.user.ui.sse.testing.SseFrames.allDataOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingFramesTest {
    private static final ObjectWriter WRITER = Json.createWriterFor(new TypeToken<>() {});
    /// A deadlock safety net for the cross-thread test.
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(30);

    private final PendingFrames pendingFrames = new PendingFrames();
    private @Nullable ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void takesFramesInTheOrderOffered() {
        SseFrame first = createUnsuperseded("first");
        SseFrame second = createUnsuperseded("second");
        SseFrame third = createUnsuperseded("third");

        pendingFrames.offer(first);
        pendingFrames.offer(second);
        pendingFrames.offer(third);

        assertThat(pendingFrames.isEmpty()).isFalse();
        assertThat(pendingFrames.takeAll()).containsExactly(first, second, third);
        assertThat(pendingFrames.isEmpty()).isTrue();
    }

    /// A newer frame with the same key replaces the older frame and takes the newer frame's position, so the newer frame follows the snapshot offered
    /// between the two.
    @Test
    void keepsTheNewestFramePerKeyAtTheNewestFramesPosition() {
        SseFrame olderUpdate = createSuperseding("user-update", "user:1");
        SseFrame snapshot = createSuperseding("users-snapshot", "users-snapshot");
        SseFrame newerUpdate = createSuperseding("user-update", "user:1");

        pendingFrames.offer(olderUpdate);
        pendingFrames.offer(snapshot);
        pendingFrames.offer(newerUpdate);

        assertThat(pendingFrames.takeAll()).containsExactly(snapshot, newerUpdate);
    }

    @Test
    void keepsEveryFrameNothingSupersedes() {
        SseFrame first = createUnsuperseded("ticket-removed");
        SseFrame second = createUnsuperseded("ticket-removed");

        pendingFrames.offer(first);
        pendingFrames.offer(second);

        assertThat(pendingFrames.takeAll()).containsExactly(first, second);
    }

    @Test
    void refusesAFrameOnceCapacityFramesArePendingAndAcceptsAgainOnceTaken() {
        for (int i = 0; i < PendingFrames.CAPACITY; i++) {
            assertThat(pendingFrames.offer(createUnsuperseded("update"))).isEqualTo(ACCEPTED);
        }

        assertThat(pendingFrames.offer(createUnsuperseded("update"))).isEqualTo(OVERFLOWED);
        assertThat(pendingFrames.takeAll()).hasSize(PendingFrames.CAPACITY);
        assertThat(pendingFrames.offer(createUnsuperseded("update"))).isEqualTo(ACCEPTED);
    }

    @Test
    void theFramesTakenAreReadOnly() {
        pendingFrames.offer(createUnsuperseded("update"));

        List<SseFrame> takenFrames = pendingFrames.takeAll();

        assertThatThrownBy(() -> takenFrames.set(0, createUnsuperseded("other"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void takesNothingWhenNothingIsPending() {
        assertThat(pendingFrames.isEmpty()).isTrue();
        assertThat(pendingFrames.takeAll()).isEmpty();
    }

    /// Several threads offer while another keeps taking: every frame offered is taken exactly once, and each thread's frames stay in the order it offered them.
    @Test
    void framesOfferedConcurrentlyAreEachTakenOnceInEachOfferersOrder() {
        int offerers = 4;
        int framesPerOfferer = PendingFrames.CAPACITY / offerers;
        executor = Executors.newFixedThreadPool(offerers + 1);
        ExecutorService testExecutor = executor;
        CompletableFuture<List<Void>> allOffers = IntStream.range(0, offerers)
                                                           .mapToObj(offerer -> CompletableFuture.runAsync(() -> {
                                                               for (int i = 0; i < framesPerOfferer; i++) {
                                                                   SseFrame frame = SseFrame.createUnsuperseded(eventNameOf(offerer), i, WRITER);
                                                                   assertThat(pendingFrames.offer(frame)).isEqualTo(ACCEPTED);
                                                               }
                                                           }, testExecutor))
                                                           .collect(toFutureOfList());
        var takenFrames = new ArrayList<SseFrame>();
        // The taker runs alongside the offerers, as a container thread drains while features offer.
        CompletableFuture<Void> takerTask = CompletableFuture.runAsync(() -> {
            while (!allOffers.isDone()) {
                takenFrames.addAll(pendingFrames.takeAll());
            }
        }, testExecutor);

        assertThat(takerTask).succeedsWithin(ASSERTION_TIMEOUT);
        assertThat(allOffers).succeedsWithin(ASSERTION_TIMEOUT);
        takenFrames.addAll(pendingFrames.takeAll());

        assertThat(takenFrames).hasSize(offerers * framesPerOfferer);
        String encodedFrames = encodeAll(takenFrames);
        for (int offerer = 0; offerer < offerers; offerer++) {
            assertThat(allDataOf(encodedFrames, eventNameOf(offerer)))
                    .containsExactlyElementsOf(IntStream.range(0, framesPerOfferer).mapToObj(String::valueOf).toList());
        }
    }

    private static String eventNameOf(int offerer) {
        return "offerer-" + offerer;
    }


    private static SseFrame createUnsuperseded(String eventName) {
        return SseFrame.createUnsuperseded(eventName, eventName, WRITER);
    }

    private static SseFrame createSuperseding(String eventName, String key) {
        return SseFrame.createSuperseding(eventName, key, eventName, WRITER);
    }
}
