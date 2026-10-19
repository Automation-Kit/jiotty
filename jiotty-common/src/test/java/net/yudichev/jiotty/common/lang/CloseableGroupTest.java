package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static org.assertj.core.api.Assertions.assertThat;

class CloseableGroupTest {
    /// A deadlock safety net for the concurrent case, not a poll interval.
    private static final Duration ASSERTION_TIMEOUT = Duration.ofSeconds(30);
    private static final int ADDER_COUNT = 4;

    private final CloseableGroup group = new CloseableGroup();
    private final List<String> closedMemberNames = new ArrayList<>(2);
    private final ExecutorService adders = Executors.newFixedThreadPool(ADDER_COUNT);

    @AfterEach
    void tearDown() {
        adders.shutdownNow();
    }

    @Test
    void closesEveryMemberOnceNewestFirst() {
        group.add(() -> closedMemberNames.add("first"));
        group.add(() -> closedMemberNames.add("second"));

        group.close();
        group.close();

        assertThat(closedMemberNames).containsExactly("second", "first");
    }

    @Test
    void closesAMemberAddedAfterTheGroupClosedAtOnce() {
        group.close();

        group.add(() -> closedMemberNames.add("late"));

        assertThat(closedMemberNames).containsExactly("late");
    }

    @Test
    void aMemberThatThrowsDoesNotStopTheRest() {
        group.add(() -> closedMemberNames.add("first"));
        group.add(() -> {
            throw new IllegalStateException("broken member");
        });

        group.close();

        assertThat(closedMemberNames).containsExactly("first");
    }

    /// A member that adds another while the group closes has it closed on the spot, because the group has already closed.
    @Test
    void closesAMemberAddedByAnotherMemberWhileClosing() {
        group.add(() -> group.add(() -> closedMemberNames.add("added while closing")));

        group.close();

        assertThat(closedMemberNames).containsExactly("added while closing");
    }

    @Test
    void isClosedOnceClosed() {
        assertThat(group.isClosed()).isFalse();

        group.close();

        assertThat(group.isClosed()).isTrue();
    }

    @Test
    void closesEveryMemberExactlyOnceWhenAddsRaceTheClose() {
        int membersPerAdder = 1_000;
        List<AtomicInteger> closeCounts = new ArrayList<>(ADDER_COUNT * membersPerAdder);
        List<CompletableFuture<Void>> adds = new ArrayList<>(ADDER_COUNT);
        for (int adder = 0; adder < ADDER_COUNT; adder++) {
            List<AtomicInteger> counts = new ArrayList<>(membersPerAdder);
            for (int i = 0; i < membersPerAdder; i++) {
                counts.add(new AtomicInteger());
            }
            closeCounts.addAll(counts);
            adds.add(CompletableFuture.runAsync(() -> counts.forEach(count -> group.add(count::incrementAndGet)), adders));
        }

        group.close();

        assertThat(adds.stream().collect(toFutureOfList())).succeedsWithin(ASSERTION_TIMEOUT);
        assertThat(closeCounts).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
    }
}
