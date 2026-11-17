package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

import java.time.LocalDate;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/// The data that [UserPersistence] stores for a user in addition to the user's profile.
///
/// @param userId                  internal user id
/// @param activityDaysByKind      the UTC days recorded by [UserPersistence#recordActivity], keyed by the kind of activity recorded with each day
/// @param activityHistoryDisabled whether [UserPersistence#setActivityHistoryDisabled] has switched the recording of activity days off
/// @param attachmentsByName       the JSON value of each attachment stored by [UserPersistence#putAttachment], keyed by the attachment's name
public record UserRecord(String userId,
                         SetMultimap<String, LocalDate> activityDaysByKind,
                         boolean activityHistoryDisabled,
                         Map<String, String> attachmentsByName) implements StringFormattable {
    public UserRecord {
        checkNotNull(userId, "userId");
        activityDaysByKind = ImmutableSetMultimap.copyOf(activityDaysByKind);
        attachmentsByName = ImmutableMap.copyOf(attachmentsByName);
    }

    /// @return a copy of this record without its activity days before `earliestDay`
    public UserRecord withoutDaysBefore(LocalDate earliestDay) {
        boolean anyDayBefore = false;
        for (LocalDate activityDay : activityDaysByKind.values()) {
            if (activityDay.isBefore(earliestDay)) {
                anyDayBefore = true;
                break;
            }
        }
        if (!anyDayBefore) {
            return this;
        }
        int kindCount = activityDaysByKind.keySet().size();
        var remainingActivityDaysByKindBuilder = ImmutableSetMultimap.<String, LocalDate>builderWithExpectedKeys(kindCount)
                                                                     .expectedValuesPerKey(activityDaysByKind.size() / kindCount);
        activityDaysByKind.forEach((kind, activityDay) -> {
            if (!activityDay.isBefore(earliestDay)) {
                remainingActivityDaysByKindBuilder.put(kind, activityDay);
            }
        });
        return new UserRecord(userId, remainingActivityDaysByKindBuilder.build(), activityHistoryDisabled, attachmentsByName);
    }

    @Override
    public String toString() {
        return toString(128);
    }

    /// Renders [#userId()], [#activityHistoryDisabled()], and the number of activity days and of attachments in place of the days and the attachment values,
    /// which are personal data.
    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "UserRecord[userId=");
        Append.to(appendable, userId);
        Append.to(appendable, ", activityDayCount=");
        Append.to(appendable, activityDaysByKind.size());
        Append.to(appendable, ", activityHistoryDisabled=");
        Append.to(appendable, activityHistoryDisabled);
        Append.to(appendable, ", attachmentCount=");
        Append.to(appendable, attachmentsByName.size());
        Append.to(appendable, ']');
    }
}
