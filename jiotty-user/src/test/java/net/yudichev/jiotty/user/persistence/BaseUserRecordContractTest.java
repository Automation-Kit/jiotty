package net.yudichev.jiotty.user.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.persistence.RecordingUserRecordListener.Changed;
import net.yudichev.jiotty.user.persistence.RecordingUserRecordListener.DaysPurged;
import net.yudichev.jiotty.user.persistence.RecordingUserRecordListener.RecordEvent;
import net.yudichev.jiotty.user.persistence.RecordingUserRecordListener.Removed;
import org.assertj.core.api.AbstractListAssert;
import org.assertj.core.api.ObjectAssert;
import org.assertj.core.api.OptionalAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static java.time.ZoneOffset.UTC;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.user.persistence.UserPersistenceTestTimeouts.DELIVERY_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.optional;

/// Contract tests for the activity days, the [UserRecord#activityHistoryDisabled()] flag and the attachments that a [UserPersistence] holds for each user,
/// and for the deliveries of [UserPersistence#subscribe(LocalDate, UserPersistence.UserRecordListener)]. A subclass runs them against one implementation.
public abstract class BaseUserRecordContractTest extends BaseUserPersistenceContractTest {
    private static final String KIND = "CHARGE_STARTED";
    private static final String OTHER_KIND = "APP_USED";
    private static final LocalDate DAY = LocalDate.ofInstant(START, UTC);
    /// An `earliestDay` before every day that a test records, so that a subscription made with it receives every recorded day.
    private static final LocalDate DAY_BEFORE_ALL_ACTIVITY = DAY.minusYears(1);
    private static final String ABSENT_USER_ID = "u-never-existed";
    private static final String ATTACHMENT = "devices";
    private static final String OTHER_ATTACHMENT = "integrations";
    private static final String JSON = "{\"platform\":\"ios\",\"count\":1}";
    private static final String OTHER_JSON = "{\"platform\":\"android\",\"count\":2}";

    private RecordingUserRecordListener recordListener;

    @BeforeEach
    final void setUpContract() {
        recordListener = new RecordingUserRecordListener();
    }

    @Test
    void recordsADayOncePerUserKindAndDay() {
        String userId = createUser(IDENTITY, "Alex").id();

        assertThatRecordingActivity(userId, KIND, DAY).isEqualTo(ActivityRecordOutcome.RECORDED);
        assertThatRecordingActivity(userId, KIND, DAY).isEqualTo(ActivityRecordOutcome.RECORDED);
        assertThatRecordingActivity(userId, OTHER_KIND, DAY).isEqualTo(ActivityRecordOutcome.RECORDED);
        assertThatRecordingActivity(userId, KIND, DAY.plusDays(1)).isEqualTo(ActivityRecordOutcome.RECORDED);

        var expectedDaysByKind = ImmutableSetMultimap.of(KIND, DAY, OTHER_KIND, DAY, KIND, DAY.plusDays(1));
        assertThatRecord(userId).hasValueSatisfying(record -> assertThat(record.activityDaysByKind()).isEqualTo(expectedDaysByKind));
    }

    /// [UserPersistence#recordActivity] moves the user's most recent activity forward to the start of the recorded day, and never back to an earlier day, so
    /// [UserPersistence#listInactiveSince] never returns the user for a cutoff at or before the start of a day recorded for them.
    @Test
    void recordingADayMovesTheMostRecentActivityForwardAndNeverBack() {
        String userId = createUser(IDENTITY, "Alex").id();
        Instant cutoff = DAY.plusDays(4).atStartOfDay(UTC).toInstant();
        assertThatIdsOfUsersInactiveSince(cutoff).containsExactly(userId);

        recordActivity(userId, KIND, DAY.plusDays(5));
        recordActivity(userId, KIND, DAY.plusDays(2));

        assertThatIdsOfUsersInactiveSince(cutoff).isEmpty();
    }

    @Test
    void recordsNothingForAUserWithNoRow() {
        String userId = createUser(IDENTITY, "Alex").id();
        await(userPersistence().hardDelete(userId));
        subscribe();

        assertThatRecordingActivity(userId, KIND, DAY).isEqualTo(ActivityRecordOutcome.ABSENT);
        assertThatRecordingActivity(ABSENT_USER_ID, KIND, DAY).isEqualTo(ActivityRecordOutcome.ABSENT);

        assertThatRecord(userId).isEmpty();
        assertThat(recordListener.changes()).isEmpty();
    }

    @Test
    void aUserWithHistoryDisabledKeepsNoDayAndStillCountsAsActive() {
        String userId = createUser(IDENTITY, "Alex").id();
        await(userPersistence().setActivityHistoryDisabled(userId, true));
        subscribe();

        assertThatRecordingActivity(userId, KIND, DAY.plusDays(5)).isEqualTo(ActivityRecordOutcome.HISTORY_OFF);

        assertThatRecord(userId).hasValueSatisfying(record -> assertThat(record.activityDaysByKind().isEmpty()).isTrue());
        assertThatIdsOfUsersInactiveSince(DAY.plusDays(4).atStartOfDay(UTC).toInstant()).isEmpty();
        assertThat(recordListener.changes()).isEmpty();
    }

    @Test
    void disablingHistoryDeletesTheRecordedDaysAndEnablingItResumesRecording() {
        String userId = createUser(IDENTITY, "Alex").id();
        recordActivity(userId, KIND, DAY);

        assertThat(userPersistence().setActivityHistoryDisabled(userId, true)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);
        assertThatRecord(userId).hasValueSatisfying(record -> {
            assertThat(record.activityHistoryDisabled()).isTrue();
            assertThat(record.activityDaysByKind().isEmpty()).isTrue();
        });
        assertThat(userPersistence().setActivityHistoryDisabled(userId, false)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);
        recordActivity(userId, KIND, DAY.plusDays(1));

        assertThatRecord(userId).hasValueSatisfying(record -> {
            assertThat(record.activityHistoryDisabled()).isFalse();
            assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY.plusDays(1)));
        });
    }

    @Test
    void switchingHistoryReportsAUserWithNoRow() {
        assertThat(userPersistence().setActivityHistoryDisabled(ABSENT_USER_ID, true)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(false);
    }

    @Test
    void storesAnAttachmentUnderItsNameAndReplacesIt() {
        String userId = createUser(IDENTITY, "Alex").id();

        assertThat(userPersistence().putAttachment(userId, ATTACHMENT, JSON)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);
        assertThat(userPersistence().putAttachment(userId, OTHER_ATTACHMENT, OTHER_JSON)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);
        assertThat(userPersistence().putAttachment(userId, ATTACHMENT, OTHER_JSON)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);

        Map<String, JsonNode> expectedAttachmentsByName = Map.of(ATTACHMENT, Json.parse(OTHER_JSON), OTHER_ATTACHMENT, Json.parse(OTHER_JSON));
        assertThatRecord(userId).hasValueSatisfying(record -> assertThat(parseAttachments(record)).isEqualTo(expectedAttachmentsByName));
    }

    @Test
    void storesNoAttachmentForAUserWithNoRow() {
        assertThat(userPersistence().putAttachment(ABSENT_USER_ID, ATTACHMENT, JSON)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(false);
    }

    @Test
    void rejectsAnAttachmentOverTheLengthLimit() {
        String userId = createUser(IDENTITY, "Alex").id();
        String overlongJson = '"' + "a".repeat(UserPersistence.MAX_ATTACHMENT_LENGTH) + '"';

        assertThatThrownBy(() -> userPersistence().putAttachment(userId, ATTACHMENT, overlongJson)).isInstanceOf(IllegalArgumentException.class);
    }

    /// A soft-deleted user still exists, so a write to the user's record is applied and delivered as for any other user.
    @Test
    void writesToASoftDeletedUserApplyAndAreDelivered() {
        String userId = createUser(IDENTITY, "Alex").id();
        await(userPersistence().softDelete(userId));
        subscribe();

        assertThatRecordingActivity(userId, KIND, DAY).isEqualTo(ActivityRecordOutcome.RECORDED);
        assertThat(userPersistence().putAttachment(userId, ATTACHMENT, JSON)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);
        assertThat(userPersistence().setActivityHistoryDisabled(userId, true)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(true);

        assertThat(recordListener.changes()).satisfiesExactly(
                dayEvent -> assertChanged(dayEvent, record -> assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY))),
                attachmentEvent -> assertChanged(attachmentEvent,
                                                 record -> assertThat(parseAttachments(record)).isEqualTo(Map.of(ATTACHMENT, Json.parse(JSON)))),
                historySwitchEvent -> assertChanged(historySwitchEvent, record -> assertThat(record.activityHistoryDisabled()).isTrue()));
        assertThatRecord(userId).hasValueSatisfying(record -> {
            assertThat(record.activityHistoryDisabled()).isTrue();
            assertThat(parseAttachments(record)).isEqualTo(Map.of(ATTACHMENT, Json.parse(JSON)));
        });
    }

    @Test
    void deliversEveryUsersRecordAsTheImageCutAtTheSubscriptionsEarliestDay() {
        String userId = createUser(IDENTITY, "Alex").id();
        String softDeletedUserId = createUser(OTHER_IDENTITY, "Sam", "sam@example.com").id();
        recordActivity(userId, KIND, DAY.minusDays(1));
        recordActivity(userId, KIND, DAY);
        await(userPersistence().putAttachment(userId, ATTACHMENT, JSON));
        await(userPersistence().setActivityHistoryDisabled(softDeletedUserId, true));
        await(userPersistence().softDelete(softDeletedUserId));

        userPersistence().subscribe(DAY, recordListener);

        assertThat(recordListener.image()).succeedsWithin(DELIVERY_TIMEOUT).asInstanceOf(list(UserRecord.class)).satisfiesExactlyInAnyOrder(
                record -> {
                    assertThat(record.userId()).isEqualTo(userId);
                    assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY));
                    assertThat(record.activityHistoryDisabled()).isFalse();
                    assertThat(parseAttachments(record)).isEqualTo(Map.of(ATTACHMENT, Json.parse(JSON)));
                },
                record -> {
                    assertThat(record.userId()).isEqualTo(softDeletedUserId);
                    assertThat(record.activityHistoryDisabled()).isTrue();
                    assertThat(record.attachmentsByName()).isEmpty();
                });
    }

    @Test
    void deliversTheWholeRecordAfterEachWriteThatChangedItInCommitOrder() {
        String userId = createUser(IDENTITY, "Alex").id();
        subscribe();

        recordActivity(userId, KIND, DAY);
        await(userPersistence().putAttachment(userId, ATTACHMENT, JSON));
        await(userPersistence().setActivityHistoryDisabled(userId, true));

        assertThat(recordListener.changes()).satisfiesExactly(dayEvent -> assertChanged(dayEvent, record -> {
                                                                  assertThat(record.userId()).isEqualTo(userId);
                                                                  assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY));
                                                                  assertThat(record.attachmentsByName()).isEmpty();
                                                              }),
                                                              attachmentEvent -> assertChanged(attachmentEvent, record -> {
                                                                  assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY));
                                                                  assertThat(parseAttachments(record)).isEqualTo(Map.of(ATTACHMENT, Json.parse(JSON)));
                                                                  assertThat(record.activityHistoryDisabled()).isFalse();
                                                              }),
                                                              historySwitchEvent -> assertChanged(historySwitchEvent, record -> {
                                                                  assertThat(record.activityDaysByKind().isEmpty()).isTrue();
                                                                  assertThat(parseAttachments(record)).isEqualTo(Map.of(ATTACHMENT, Json.parse(JSON)));
                                                                  assertThat(record.activityHistoryDisabled()).isTrue();
                                                              }));
    }

    @Test
    void aChangedRecordIsCutAtTheSubscriptionsEarliestDay() {
        String userId = createUser(IDENTITY, "Alex").id();
        recordActivity(userId, KIND, DAY.minusDays(1));
        userPersistence().subscribe(DAY, recordListener);

        recordActivity(userId, KIND, DAY);

        assertThat(recordListener.changes()).singleElement().satisfies(
                dayEvent -> assertChanged(dayEvent, record -> assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY))));
    }

    @Test
    void staysSilentAfterAWriteThatLeavesTheRecordAsItWas() {
        String userId = createUser(IDENTITY, "Alex").id();
        recordActivity(userId, KIND, DAY);
        await(userPersistence().putAttachment(userId, ATTACHMENT, JSON));
        await(userPersistence().setActivityHistoryDisabled(userId, false));
        subscribe();

        recordActivity(userId, KIND, DAY);
        await(userPersistence().putAttachment(userId, ATTACHMENT, JSON));
        await(userPersistence().setActivityHistoryDisabled(userId, false));
        await(userPersistence().updateProfile(userId, createProfileInput("Alexandra")));
        await(userPersistence().purgeActivityDaysBefore(DAY));

        assertThat(recordListener.changes()).isEmpty();
    }

    /// A write that changes an attachment and a second write that restores the attachment's original value produce one delivery each, although the record
    /// after both writes equals the record before them.
    @Test
    void anAttachmentChangedAndChangedBackIsDeliveredEachTime() {
        String userId = createUser(IDENTITY, "Alex").id();
        await(userPersistence().putAttachment(userId, ATTACHMENT, JSON));
        subscribe();

        await(userPersistence().putAttachment(userId, ATTACHMENT, OTHER_JSON));
        await(userPersistence().putAttachment(userId, ATTACHMENT, JSON));

        assertThat(recordListener.changes()).satisfiesExactly(
                attachmentChange -> assertChanged(attachmentChange,
                                                  record -> assertThat(parseAttachments(record)).isEqualTo(Map.of(ATTACHMENT, Json.parse(OTHER_JSON)))),
                attachmentReversal -> assertChanged(attachmentReversal,
                                                    record -> assertThat(parseAttachments(record)).isEqualTo(Map.of(ATTACHMENT, Json.parse(JSON)))));
    }

    @Test
    void purgesEveryUsersDaysBeforeADayAndAnnouncesIt() {
        String userId = createUser(IDENTITY, "Alex").id();
        String otherUserId = createUser(OTHER_IDENTITY, "Sam", "sam@example.com").id();
        recordActivity(userId, KIND, DAY.minusDays(2));
        recordActivity(userId, OTHER_KIND, DAY.minusDays(1));
        recordActivity(userId, KIND, DAY);
        recordActivity(otherUserId, KIND, DAY.minusDays(1));
        subscribe();

        assertThat(userPersistence().purgeActivityDaysBefore(DAY)).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(3);

        assertThatRecord(userId).hasValueSatisfying(record -> assertThat(record.activityDaysByKind()).isEqualTo(ImmutableSetMultimap.of(KIND, DAY)));
        assertThatRecord(otherUserId).hasValueSatisfying(record -> assertThat(record.activityDaysByKind().isEmpty()).isTrue());
        assertThat(recordListener.changes()).containsExactly(new DaysPurged(DAY));
    }

    @Test
    void aHardDeleteRemovesTheRecordAndAnnouncesTheRemoval() {
        String userId = createUser(IDENTITY, "Alex").id();
        recordActivity(userId, KIND, DAY);
        await(userPersistence().putAttachment(userId, ATTACHMENT, JSON));
        subscribe();

        await(userPersistence().hardDelete(userId));

        assertThatRecord(userId).isEmpty();
        assertThat(recordListener.changes()).containsExactly(new Removed(userId));
    }

    @Test
    void reAnnouncesTheRemovalWhenAHardDeleteFindsTheUserAlreadyGone() {
        String userId = createUser(IDENTITY, "Alex").id();
        await(userPersistence().hardDelete(userId));
        subscribe();

        await(userPersistence().hardDelete(userId));

        assertThat(recordListener.changes()).containsExactly(new Removed(userId));
    }

    @Test
    void hardDeleteCompletesOnceARecordSubscriberHasAppliedTheRemoval() {
        String userId = createUser(IDENTITY, "Alex").id();
        var removalAcknowledgement = new CompletableFuture<Void>();
        var deliveredRemovalUserId = new CompletableFuture<String>();
        userPersistence().subscribe(DAY_BEFORE_ALL_ACTIVITY, new RecordingUserRecordListener() {
            @Override
            public CompletableFuture<?> onRemoved(String removedUserId) {
                deliveredRemovalUserId.complete(removedUserId);
                return removalAcknowledgement;
            }
        });

        CompletableFuture<Void> hardDelete = userPersistence().hardDelete(userId);

        assertThat(deliveredRemovalUserId).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(userId);
        assertThat(hardDelete).isNotDone();
        removalAcknowledgement.complete(null);
        assertThat(hardDelete).succeedsWithin(DELIVERY_TIMEOUT);
    }

    @Test
    void hardDeleteFailsWhenARecordSubscriberFailsToApplyTheRemoval() {
        String userId = createUser(IDENTITY, "Alex").id();
        userPersistence().subscribe(DAY_BEFORE_ALL_ACTIVITY, new RecordingUserRecordListener() {
            @Override
            public CompletableFuture<?> onRemoved(String removedUserId) {
                return failedFuture(new RuntimeException("could not apply the removal"));
            }
        });

        assertThat(userPersistence().hardDelete(userId)).failsWithin(DELIVERY_TIMEOUT);
    }

    /// The write commits before the listeners run, so a listener that throws must not fail the write or stop the delivery to the other listeners. The
    /// subscription of the listener that throws ends.
    @Test
    void containsAFailingSubscriberSoTheWriteStillSucceeds() {
        String userId = createUser(IDENTITY, "Alex").id();
        var failingListener = new RecordingUserRecordListener() {
            @Override
            public void onChanged(UserRecord record) {
                throw new RuntimeException("listener boom");
            }
        };
        userPersistence().subscribe(DAY_BEFORE_ALL_ACTIVITY, failingListener);
        subscribe();

        assertThatRecordingActivity(userId, KIND, DAY).isEqualTo(ActivityRecordOutcome.RECORDED);

        assertThat(recordListener.changes()).hasSize(1);
        assertThat(failingListener.failure()).succeedsWithin(DELIVERY_TIMEOUT).isInstanceOf(RuntimeException.class);
    }

    @Test
    void stopsNotifyingAnUnsubscribedListener() {
        String userId = createUser(IDENTITY, "Alex").id();
        Closeable subscription = subscribe();
        assertThat(recordListener.image()).succeedsWithin(DELIVERY_TIMEOUT);

        subscription.close();
        recordActivity(userId, KIND, DAY);

        assertThat(recordListener.changes()).isEmpty();
    }

    private Closeable subscribe() {
        return userPersistence().subscribe(DAY_BEFORE_ALL_ACTIVITY, recordListener);
    }

    private void recordActivity(String userId, String kind, LocalDate day) {
        await(userPersistence().recordActivity(userId, kind, day));
    }

    private ObjectAssert<ActivityRecordOutcome> assertThatRecordingActivity(String userId, String kind, LocalDate day) {
        return assertThat(userPersistence().recordActivity(userId, kind, day)).succeedsWithin(DELIVERY_TIMEOUT);
    }

    /// @return an assertion on the user's record, which [UserPersistence#readRecord] reads when this method is called
    private OptionalAssert<UserRecord> assertThatRecord(String userId) {
        return assertThat(userPersistence().readRecord(userId)).as("the record of user %s", userId)
                                                               .succeedsWithin(DELIVERY_TIMEOUT)
                                                               .asInstanceOf(optional(UserRecord.class));
    }

    private AbstractListAssert<?, List<? extends String>, String, ObjectAssert<String>> assertThatIdsOfUsersInactiveSince(Instant cutoff) {
        return assertThat(userPersistence().listInactiveSince(cutoff, 100)).succeedsWithin(DELIVERY_TIMEOUT)
                                                                           .asInstanceOf(list(UserProfile.class))
                                                                           .extracting(UserProfile::id);
    }

    private static void assertChanged(RecordEvent event, Consumer<UserRecord> recordRequirements) {
        assertThat(event).isInstanceOfSatisfying(Changed.class, changed -> recordRequirements.accept(changed.record()));
    }

    /// @return the record's attachments, each parsed as JSON so that a comparison ignores key order and whitespace, which a store may change
    private static Map<String, JsonNode> parseAttachments(UserRecord record) {
        return Maps.transformValues(record.attachmentsByName(), Json::parse);
    }
}
