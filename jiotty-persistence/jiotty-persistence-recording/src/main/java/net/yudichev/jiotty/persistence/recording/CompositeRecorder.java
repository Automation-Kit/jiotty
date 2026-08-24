package net.yudichev.jiotty.persistence.recording;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.CompositeException;
import net.yudichev.jiotty.common.lang.Runnables;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

public final class CompositeRecorder<R> extends BaseIdempotentCloseable implements Recorder<R> {
    private final List<GuardedRecorder<R>> recorders;
    private final TaskFailureReporter taskFailureReporter;

    @SafeVarargs
    public CompositeRecorder(TaskFailureReporter taskFailureReporter, Recorder<R>... recorders) {
        this(ImmutableList.copyOf(recorders), taskFailureReporter);
    }

    public CompositeRecorder(List<Recorder<R>> recorders, TaskFailureReporter taskFailureReporter) {
        this.taskFailureReporter = checkNotNull(taskFailureReporter);
        this.recorders = checkNotNull(recorders).stream()
                                                .map(recorder -> new GuardedRecorder<>(recorder, "recording to " + recorder))
                                                .collect(toImmutableList());
    }

    @Override
    public void record(Instant timestamp, R recordable) {
        forEachRecorder(recorder -> recorder.record(timestamp, recordable));
    }

    @Override
    public void record(DestinationType destinationType, Instant timestamp, R recordable) {
        forEachRecorder(recorder -> recorder.record(destinationType, timestamp, recordable));
    }

    /// Records to every destination, each contained on its own: a throwing destination loses its copy and reports the failure, and the remaining destinations
    /// record. The report names the recorder, and the recordable is left out of it — a recordable can carry the subject's own data.
    private void forEachRecorder(Consumer<Recorder<R>> consumer) {
        for (GuardedRecorder<R> guarded : recorders) {
            Runnables.runGuarded(guarded.failureDescription(), () -> consumer.accept(guarded.recorder()), taskFailureReporter::onTaskException);
        }
    }

    @Override
    public void doClose() {
        CompositeException.runForAll(recorders, guarded -> guarded.recorder().close());
    }

    /// One destination and the description its failures are reported under, rendered once because the reporting path runs per recorded value per destination.
    private record GuardedRecorder<R>(Recorder<R> recorder, String failureDescription) {
        private GuardedRecorder {
            checkNotNull(recorder);
        }
    }
}
