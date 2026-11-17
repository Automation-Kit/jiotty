package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class UserRecordTest {
    private static final String USER_ID = "u0164813413255149e4frk";
    private static final String KIND = "CHARGE_STARTED";
    private static final String OTHER_KIND = "APP_USED";
    private static final LocalDate DAY = LocalDate.of(2026, 3, 1);
    private static final String ATTACHMENT = "devices";
    private static final String JSON = "{\"platform\":\"ios\",\"appVersion\":\"1.4.2\"}";

    /// [UserRecord#toString()] and [UserRecord#formatTo(Appendable)] write a record into log lines, alerts and exception messages, so their text holds the
    /// number of activity days and the number of attachments, and none of the days, activity kinds, attachment names or attachment values.
    @Test
    void toStringRendersTheNumberOfDaysAndAttachmentsAndKeepsThePseudonymousId() {
        var record = new UserRecord(USER_ID, ImmutableSetMultimap.of(KIND, DAY, OTHER_KIND, DAY.plusDays(1)), true, Map.of(ATTACHMENT, JSON));

        assertThat(record).asString()
                          .isEqualTo("UserRecord[userId=u0164813413255149e4frk, activityDayCount=2, activityHistoryDisabled=true, attachmentCount=1]")
                          .doesNotContain(DAY.toString(), DAY.plusDays(1).toString())
                          .doesNotContain(KIND, OTHER_KIND, ATTACHMENT)
                          .doesNotContain("ios", "1.4.2");
    }

    @Test
    void accessorsKeepTheValuesTheRenderingLeavesOut() {
        var record = new UserRecord(USER_ID, ImmutableSetMultimap.of(KIND, DAY), false, Map.of(ATTACHMENT, JSON));

        assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY));
        assertThat(record.attachmentsByName()).isEqualTo(Map.of(ATTACHMENT, JSON));
    }

    @Test
    void holdsACopyOfTheDaysAndAttachmentsItIsGiven() {
        SetMultimap<String, LocalDate> activityDaysByKind = HashMultimap.create();
        activityDaysByKind.put(KIND, DAY);
        var attachmentsByName = new HashMap<String, String>();
        attachmentsByName.put(ATTACHMENT, JSON);

        var record = new UserRecord(USER_ID, activityDaysByKind, false, attachmentsByName);
        activityDaysByKind.put(KIND, DAY.plusDays(1));
        attachmentsByName.put("integrations", "[]");

        assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY));
        assertThat(record.attachmentsByName()).isEqualTo(Map.of(ATTACHMENT, JSON));
    }

    @Test
    void rejectsANullUserId() {
        assertThatThrownBy(() -> new UserRecord(null, ImmutableSetMultimap.of(), false, Map.of())).isInstanceOf(NullPointerException.class)
                                                                                                  .hasMessageContaining("userId");
    }

    @ParameterizedTest
    @MethodSource
    void withoutDaysBeforeReturnsTheSameRecordWhenNoDayIsBeforeTheEarliestDay(UserRecord record) {
        assertThat(record.withoutDaysBefore(DAY)).isSameAs(record);
    }

    static Stream<Arguments> withoutDaysBeforeReturnsTheSameRecordWhenNoDayIsBeforeTheEarliestDay() {
        return Stream.of(
                // every day on or after the earliest day
                arguments(new UserRecord(USER_ID, ImmutableSetMultimap.of(KIND, DAY, OTHER_KIND, DAY.plusDays(1)), false, Map.of(ATTACHMENT, JSON))),
                // no activity day
                arguments(new UserRecord(USER_ID, ImmutableSetMultimap.of(), true, Map.of())));
    }

    /// [UserRecord#withoutDaysBefore] returns a record with the same user id, [UserRecord#activityHistoryDisabled()] flag and attachments, holding the days
    /// that are on or after the earliest day. A kind whose days are all before the earliest day is absent from the returned record.
    @Test
    void withoutDaysBeforeDropsEachDayBeforeTheEarliestDayAndCarriesTheRestOver() {
        var record = new UserRecord(USER_ID,
                                    ImmutableSetMultimap.of(KIND, DAY.minusDays(1), KIND, DAY, KIND, DAY.plusDays(1), OTHER_KIND, DAY.minusDays(2)),
                                    true,
                                    Map.of(ATTACHMENT, JSON));

        assertThat(record.withoutDaysBefore(DAY)).isEqualTo(new UserRecord(USER_ID,
                                                                           ImmutableSetMultimap.of(KIND, DAY, KIND, DAY.plusDays(1)),
                                                                           true,
                                                                           Map.of(ATTACHMENT, JSON)));
    }
}
