package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// [Closeable]s closed together, once. Every method is safe from any thread, at any time: a closeable added after the group has closed is closed at once,
/// on the thread adding it, so nothing added is ever left open. The group closes its members newest first, and a member that throws is logged and does not
/// stop the rest.
public final class CloseableGroup implements Closeable {
    private static final Logger logger = LogManager.getLogger(CloseableGroup.class);

    /// Stands in for every member once the group has closed.
    private static final Member CLOSED = new Member(Closeable.noop(), null);

    /// The newest member, linked to the older ones; `null` before the first is added, [#CLOSED] once the group has closed.
    private final AtomicReference<@Nullable Member> newest = new AtomicReference<>();

    /// Closes every member added so far; each later [#add(Closeable)] closes its own. A second call does nothing.
    @Override
    public void close() {
        // One swap takes every member: a concurrent add either lands in what is taken here, or finds the group closed and closes its own closeable.
        Member member = newest.getAndSet(CLOSED);
        while (member != null && member != CLOSED) {
            closeSafelyIfNotNull(logger, member.closeable);
            member = member.older;
        }
    }

    public void add(Closeable closeable) {
        checkNotNull(closeable, "closeable");
        while (true) {
            Member current = newest.get();
            if (current == CLOSED) {
                closeSafelyIfNotNull(logger, closeable);
                return;
            }
            if (newest.compareAndSet(current, new Member(closeable, current))) {
                return;
            }
        }
    }

    public boolean isClosed() {
        return newest.get() == CLOSED;
    }

    /// @param closeable the member's closeable
    /// @param older     the member added before this one; `null` for the first
    private record Member(Closeable closeable, @Nullable Member older) {
        private Member {
            checkNotNull(closeable, "closeable");
        }
    }
}
