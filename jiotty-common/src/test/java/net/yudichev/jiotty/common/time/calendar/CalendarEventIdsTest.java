package net.yudichev.jiotty.common.time.calendar;

import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.time.calendar.CalendarEventIds.createContentDerivedId;
import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventIdsTest {

    @Test
    void createContentDerivedId_isShortAndUrlSafe() {
        assertThat(createContentDerivedId("Swimming", "Leisure Centre")).hasSize(12)
                                                                        .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void createContentDerivedId_carriesNeitherTheTitleNorTheLocation() {
        assertThat(createContentDerivedId("Swimming", "Leisure Centre")).doesNotContain("Swimming")
                                                                        .doesNotContain("Leisure Centre");
    }

    @Test
    void createContentDerivedId_isTheSameForTheSameEntry() {
        assertThat(createContentDerivedId("Swimming", "Leisure Centre")).isEqualTo(createContentDerivedId("Swimming", "Leisure Centre"));
    }

    @Test
    void createContentDerivedId_differsForEntriesTheFieldBoundaryAloneSeparates() {
        // Without the separator both would hash "abc", and two different entries would share one id.
        assertThat(createContentDerivedId("ab", "c")).isNotEqualTo(createContentDerivedId("a", "bc"));
    }

    @Test
    void createContentDerivedId_differsForEntriesAtDifferentLocations() {
        assertThat(createContentDerivedId("Swimming", "Leisure Centre")).isNotEqualTo(createContentDerivedId("Swimming", "Pool"));
    }

    @Test
    void createContentDerivedId_identifiesAnEntryThatDeclaresNoLocationByItsTitleAlone() {
        assertThat(createContentDerivedId("Swimming", null)).hasSize(12)
                                                            .isNotEqualTo(createContentDerivedId("Sailing", null));
    }
}
