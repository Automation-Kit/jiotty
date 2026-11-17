package net.yudichev.jiotty.user.persistence;

/// The outcome of [UserPersistence#recordActivity].
public enum ActivityRecordOutcome {
    /// No user with the given id exists, so nothing was written.
    ABSENT,
    /// The user's activity days include the day passed to [UserPersistence#recordActivity], whether or not that call added it. The user's last-active
    /// instant ([UserPersistence#touchLastActive]) is at or after the start of that day in UTC.
    RECORDED,
    /// No day was written, because [UserPersistence#setActivityHistoryDisabled] has disabled the user's activity history. The user's last-active instant
    /// ([UserPersistence#touchLastActive]) is at or after the start, in UTC, of the day passed to [UserPersistence#recordActivity], as for [#RECORDED].
    HISTORY_OFF
}
