package net.yudichev.jiotty.analyticscache;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BitmapSlotSetTest {
    private static final Instant BASE = Instant.parse("2026-04-01T00:00:00Z");
    private static final long HALF_HOUR_SECONDS = 1800L;

    @Test
    void setAll_populatesEveryGridSlot_andSizeMatchesCapacity() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 5);
        set.setAll();

        assertThat(set).hasSize(5);
        assertThat(set).containsExactly(
                BASE,
                BASE.plusSeconds(1800),
                BASE.plusSeconds(3600),
                BASE.plusSeconds(5400),
                BASE.plusSeconds(7200));
    }

    @Test
    void clearAt_removesIndividualBits_keepingTheRest() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 5);
        set.setAll();
        set.clearAt(0);
        set.clearAt(3);

        assertThat(set).containsExactly(
                BASE.plusSeconds(1800),
                BASE.plusSeconds(3600),
                BASE.plusSeconds(7200));
    }

    @Test
    void contains_reportsTrueForSetSlots_falseForUnsetSlots_offGridSlots_andNonInstants() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 3);
        set.setAll();
        set.clearAt(1);

        assertThat(set.contains(BASE)).isTrue();
        assertThat(set.contains(BASE.plusSeconds(1800))).isFalse();
        assertThat(set.contains(BASE.plusSeconds(3600))).isTrue();
        // Off-grid slots → false (the substrate rejects these on read anyway, but contains() must not throw).
        assertThat(set.contains(BASE.plusSeconds(900))).isFalse();  // not aligned to 30 min
        assertThat(set.contains(BASE.plusSeconds(7200))).isFalse(); // past capacity
        assertThat(set.contains(BASE.minusSeconds(1800))).isFalse(); // before base
        assertThat(set.contains(BASE.plusNanos(1))).isFalse();       // nano-precision
        // Wrong type — Set#contains contract — must return false, not throw.
        //noinspection SuspiciousMethodCalls — deliberately probing the wrong-type contract of Set#contains
        assertThat(set.contains("not-an-instant")).isFalse();
        assertThat(set.contains(null)).isFalse();
    }

    @Test
    void firstAndLast_returnGridEndpointsOfPopulatedBits() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 10);
        set.setAll();
        set.clearAt(0);
        set.clearAt(1);
        set.clearAt(8);
        set.clearAt(9);

        assertThat(set.first()).isEqualTo(BASE.plusSeconds(3600));
        assertThat(set.last()).isEqualTo(BASE.plusSeconds(12600));
    }

    @Test
    void first_onEmptySet_throwsNoSuchElement() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 5);

        assertThatThrownBy(set::first).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(set::last).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void iterator_yieldsSlotsInAscendingOrder_independentOfClearOrder() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 5);
        set.setAll();
        set.clearAt(4);
        set.clearAt(0);
        set.clearAt(2);

        var ordered = new ArrayList<Instant>();
        set.iterator().forEachRemaining(ordered::add);

        assertThat(ordered).containsExactly(
                BASE.plusSeconds(1800),
                BASE.plusSeconds(5400));
    }

    @Test
    void indexOf_returnsNegativeForOffGridSlots() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 5);

        assertThat(set.indexOf(BASE)).isZero();
        assertThat(set.indexOf(BASE.plusSeconds(1800))).isEqualTo(1);
        assertThat(set.indexOf(BASE.plusSeconds(900))).isEqualTo(-1);    // not aligned
        assertThat(set.indexOf(BASE.plusSeconds(9000))).isEqualTo(-1);   // past capacity
        assertThat(set.indexOf(BASE.minusSeconds(1800))).isEqualTo(-1);  // before base
        assertThat(set.indexOf(BASE.plusNanos(1))).isEqualTo(-1);        // nano precision
    }

    @Test
    void isEmpty_reflectsBitCardinality() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 3);

        assertThat(set.isEmpty()).isTrue();
        set.setAll();
        assertThat(set.isEmpty()).isFalse();
        set.clearAt(0);
        set.clearAt(1);
        set.clearAt(2);
        assertThat(set.isEmpty()).isTrue();
    }

    @Test
    void zeroCapacity_isAllowedAndAlwaysEmpty() {
        var set = new BitmapSlotSet(BASE, HALF_HOUR_SECONDS, 0);
        set.setAll();

        assertThat(set).isEmpty();
        assertThatThrownBy(set::first).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(set::last).isInstanceOf(NoSuchElementException.class);
    }
}
