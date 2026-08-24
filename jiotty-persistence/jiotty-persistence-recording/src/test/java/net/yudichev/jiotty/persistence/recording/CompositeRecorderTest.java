package net.yudichev.jiotty.persistence.recording;

import net.yudichev.jiotty.common.async.TaskFailureReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CompositeRecorderTest {
    private static final Instant WHEN = Instant.parse("2026-08-24T10:00:00Z");

    @Mock
    private TaskFailureReporter taskFailureReporter;

    @Test
    void recordsToEveryDestination() {
        var first = new CollectingRecorder();
        var second = new CollectingRecorder();

        new CompositeRecorder<>(List.of(first, second), taskFailureReporter).record(WHEN, "value");

        assertThat(first.recorded).containsExactly("value");
        assertThat(second.recorded).containsExactly("value");
        verifyNoInteractions(taskFailureReporter);
    }

    /// One destination failing costs its own copy alone: the remaining destinations record, and the failure is reported.
    @Test
    void aThrowingDestinationIsReportedAndTheRestStillRecord() {
        var failure = new RuntimeException("destination is down");
        var throwing = new CollectingRecorder() {
            @Override
            public void record(Instant timestamp, String recordable) {
                throw failure;
            }
        };
        var healthy = new CollectingRecorder();

        new CompositeRecorder<>(List.of(throwing, healthy), taskFailureReporter).record(WHEN, "value");

        assertThat(healthy.recorded).containsExactly("value");
        verify(taskFailureReporter).onTaskException(contains("recording to"), any(Throwable.class));
    }

    /// The report names the destination; a recordable can carry the subject's own data, so it stays out of it.
    @Test
    void theFailureReportNamesTheDestinationAndOmitsTheRecordable() {
        var failure = new RuntimeException("boom");
        var throwing = new CollectingRecorder() {
            @Override
            public void record(Instant timestamp, String recordable) {
                throw failure;
            }

            @Override
            public String toString() {
                return "psql-destination";
            }
        };

        new CompositeRecorder<>(List.of(throwing), taskFailureReporter).record(WHEN, "user@example.com");

        verify(taskFailureReporter).onTaskException("recording to psql-destination", failure);
    }

    private static class CollectingRecorder implements Recorder<String> {
        final List<String> recorded = new ArrayList<>();

        @Override
        public void record(Instant timestamp, String recordable) {
            recorded.add(recordable);
        }

        @Override
        public void record(DestinationType destinationType, Instant timestamp, String recordable) {
            record(timestamp, recordable);
        }
    }
}
