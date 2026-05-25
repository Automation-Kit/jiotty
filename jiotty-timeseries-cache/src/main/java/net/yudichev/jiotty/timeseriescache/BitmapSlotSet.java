package net.yudichev.jiotty.timeseriescache;

import java.time.Instant;
import java.util.AbstractSet;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.SortedSet;

/// [SortedSet] view of a contiguous slot grid `slot(i) = base + i × stepSeconds` for `i ∈ [0, capacity)`. Membership is a [BitSet], so [#contains], [#clearAt],
/// [#setAll] are O(1) per slot and [#first] / [#last] are O(words). Iteration walks set bits in ascending order, which preserves the natural-order contract of
/// [SortedSet] since `stepSeconds > 0`.
///
/// Used by the substrate to represent missing-slot sets: a contiguous grid is a perfect bitmap, so the typical TreeSet<Instant> shape (RB-tree of 17 520
/// Instants for a year of half-hour slots) collapses to ~2 KB of bits.
///
/// The mutation methods are package-private — callers outside this package see only the immutable [SortedSet] surface. Mutation is performed by the substrate
/// during construction; once the set is handed to a `slotsComputation` lambda, the bits are not changed again.
///
/// `subSet` / `headSet` / `tailSet` throw [UnsupportedOperationException] — no caller uses them.
final class BitmapSlotSet extends AbstractSet<Instant> implements SortedSet<Instant> {
    private final long baseEpochSecond;
    private final long stepSeconds;
    private final int capacity;
    private final BitSet bits;

    BitmapSlotSet(Instant base, long stepSeconds, int capacity) {
        baseEpochSecond = base.getEpochSecond();
        this.stepSeconds = stepSeconds;
        this.capacity = capacity;
        bits = new BitSet(capacity);
    }

    /// Marks every slot in `[0, capacity)` as present. Used by the substrate to seed the missing-set with the full range, then [#clearAt] each cache hit.
    void setAll() {
        bits.set(0, capacity);
    }

    void clearAt(int index) {
        bits.clear(index);
    }

    /// Returns the ordinal of `slot` in this grid, or `-1` if `slot` is off-grid (wrong nanos, out-of-range, or not aligned to `stepSeconds`).
    int indexOf(Instant slot) {
        if (slot.getNano() != 0) {
            return -1;
        }
        long delta = slot.getEpochSecond() - baseEpochSecond;
        if (delta < 0 || delta % stepSeconds != 0) {
            return -1;
        }
        long index = delta / stepSeconds;
        if (index >= capacity) {
            return -1;
        }
        return (int) index;
    }

    private Instant slotAt(int index) {
        return Instant.ofEpochSecond(baseEpochSecond + index * stepSeconds);
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof Instant slot)) {
            return false;
        }
        int index = indexOf(slot);
        return index >= 0 && bits.get(index);
    }

    @Override
    public Instant first() {
        int next = bits.nextSetBit(0);
        if (next < 0) {
            throw new NoSuchElementException();
        }
        return slotAt(next);
    }

    @Override
    public Instant last() {
        if (capacity == 0) {
            throw new NoSuchElementException();
        }
        int prev = bits.previousSetBit(capacity - 1);
        if (prev < 0) {
            throw new NoSuchElementException();
        }
        return slotAt(prev);
    }

    @Override
    public int size() {
        return bits.cardinality();
    }

    @Override
    public boolean isEmpty() {
        return bits.isEmpty();
    }

    @Override
    public Iterator<Instant> iterator() {
        return new Iterator<>() {
            private int next = bits.nextSetBit(0);

            @Override
            public boolean hasNext() {
                return next >= 0;
            }

            @Override
            public Instant next() {
                if (next < 0) {
                    throw new NoSuchElementException();
                }
                Instant out = slotAt(next);
                next = bits.nextSetBit(next + 1);
                return out;
            }
        };
    }

    @Override
    public Comparator<? super Instant> comparator() {
        return null;
    }

    @Override
    public SortedSet<Instant> subSet(Instant fromElement, Instant toElement) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SortedSet<Instant> headSet(Instant toElement) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SortedSet<Instant> tailSet(Instant fromElement) {
        throw new UnsupportedOperationException();
    }
}
