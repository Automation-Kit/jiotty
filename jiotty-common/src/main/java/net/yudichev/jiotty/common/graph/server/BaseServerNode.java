package net.yudichev.jiotty.common.graph.server;

import com.google.errorprone.annotations.OverridingMethodsMustInvokeSuper;
import net.yudichev.jiotty.common.graph.BaseNode;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

public abstract class BaseServerNode extends BaseNode implements ServerNode {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final GraphRunner runner;
    private final String name;

    private final Map<Integer, Closeable> schedulesById = new HashMap<>();
    private int scheduleIdGenerator;

    protected BaseServerNode(GraphRunner runner) {
        this.runner = checkNotNull(runner);
        name = getClass().getSimpleName();
    }

    protected BaseServerNode(GraphRunner runner, String name) {
        this.runner = checkNotNull(runner);
        this.name = checkNotNull(name);
    }

    @Override
    public final boolean wave() {
        logState("[" + nodeContext().graph().waveId() + "]PRE-WAVE");
        var result = doWave();
        logState("[" + nodeContext().graph().waveId() + "]POST-WAVE");
        return result;
    }

    @Override
    public void registerInGraph() {
        runner.graph().registerNode(name, this);
    }

    protected abstract boolean doWave();

    protected void triggerMeAndParentsInNewWave(String triggeredBy) {
        triggerInNewWave(triggeredBy, () -> nodeContext().triggerMeAndParentsInNextWave());
    }

    protected void triggerInNewWave(String triggeredBy) {
        triggerInNewWave(triggeredBy, () -> nodeContext().triggerInNextWave());
    }

    protected final Closeable scheduleAndCancelOnClose(Duration delay, Runnable task) {
        Integer scheduleId = ++scheduleIdGenerator;
        Closeable schedule = runner.executor().schedule(delay, () -> {
            schedulesById.remove(scheduleId);
            task.run();
        });
        schedulesById.put(scheduleId, schedule);
        return new BaseIdempotentCloseable() {
            @Override
            protected void doClose() {
                asUnchecked(((AutoCloseable) () -> Closeable.closeSafelyIfNotNull(logger, schedulesById.remove(scheduleId)))::close);
            }

            @Override
            public String toString() {
                return schedule.toString();
            }
        };
    }

    @Override
    @OverridingMethodsMustInvokeSuper
    public void close() {
        if (!schedulesById.isEmpty()) {
            logger.debug("{}: closing {} pending scheduled tasks", name, schedulesById.size());
            schedulesById.values().forEach(closeable -> Closeable.closeSafelyIfNotNull(logger, closeable));
            schedulesById.clear();
        }
        super.close();
    }

    private void triggerInNewWave(String triggeredBy, BooleanSupplier trigger) {
        if (runner.graph().inWave()) {
            trigger.getAsBoolean();
        } else {
            runner.executor().execute(() -> {
                boolean wasNotAlreadyTriggered = trigger.getAsBoolean();
                if (wasNotAlreadyTriggered) {
                    runner.scheduleNewWave(name + ": " + triggeredBy);
                } else {
                    logger.debug("{}: not triggering as node is already pending trigger, tiggeredBy was: {}", name, triggeredBy);
                }
            });
        }
    }
}
