package net.yudichev.jiotty.user.ui.sse;

import com.google.common.collect.ImmutableList;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;

/// One client's frames offered and not yet taken for writing. Any thread offers, and the thread writing to the client takes them all at once, so the frames
/// are a linked stack whose top is swapped by compare-and-set; each node counts its depth, which bounds the stack at [#CAPACITY] frames.
final class PendingFrames {
    /// Frames offered since the last [#takeAll()] that a client may fall behind by before it is dropped: well above a full initial image of a user's
    /// displayables and options.
    public static final int CAPACITY = 64;

    /// The newest pending frame; `null` when none is pending. Frames are offered by the thread opening the stream, by the [SseChannel]'s executor as it
    /// broadcasts and pings, and by any thread sending through the client's [SseChannel.SseStream]; the container thread writing to the client takes them.
    private final AtomicReference<@Nullable Node> top = new AtomicReference<>();

    public Offer offer(SseFrame frame) {
        while (true) {
            Node current = top.get();
            int depth = current == null ? 1 : current.depth + 1;
            if (depth > CAPACITY) {
                return Offer.OVERFLOWED;
            }
            if (top.compareAndSet(current, new Node(frame, current, depth))) {
                return Offer.ACCEPTED;
            }
        }
    }

    public boolean isEmpty() {
        return top.get() == null;
    }

    /// Takes every frame offered so far, in the order offered, dropping each one a later frame supersedes. A kept frame keeps the position of the latest
    /// frame it stands for, so an update offered after a snapshot still follows that snapshot.
    public List<SseFrame> takeAll() {
        Node newestNode = top.getAndSet(null);
        if (newestNode == null) {
            return ImmutableList.of();
        }
        // Filled from the end while the stack is walked newest first, which puts the kept frames in send order.
        var keptFrames = new SseFrame[newestNode.depth];
        int firstKeptIndex = keptFrames.length;
        for (Node node = newestNode; node != null; node = node.next) {
            if (!isSupersededBy(node.frame, keptFrames, firstKeptIndex)) {
                keptFrames[--firstKeptIndex] = node.frame;
            }
        }
        // A read-only view over the array, which the caller reads once.
        return Collections.unmodifiableList(Arrays.asList(keptFrames).subList(firstKeptIndex, keptFrames.length));
    }

    /// A linear scan, because a batch is at most [#CAPACITY] frames and nearly always a handful.
    private static boolean isSupersededBy(SseFrame frame, SseFrame[] keptFrames, int firstKeptIndex) {
        for (int i = firstKeptIndex; i < keptFrames.length; i++) {
            if (keptFrames[i].supersedes(frame)) {
                return true;
            }
        }
        return false;
    }

    public enum Offer {
        ACCEPTED,
        /// The client has fallen [#CAPACITY] frames behind, and the frame was not added.
        OVERFLOWED
    }

    /// @param frame the frame offered
    /// @param next  the frame offered before it, `null` for the first since the last [#takeAll()]
    /// @param depth how many frames this node and those below it hold
    private record Node(SseFrame frame, @Nullable Node next, int depth) {
        private Node {
            checkNotNull(frame, "frame");
        }
    }
}
