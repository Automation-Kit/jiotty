package net.yudichev.jiotty.user.persistence;

/// What [UserPersistence#softDeleteIfInactiveSince] did. The three ways it can do nothing are distinct outcomes rather than one, because only
/// [#STILL_ACTIVE] is a statement about the user's activity.
public enum ConditionalSoftDeleteOutcome {
    /// The user was last active before the cutoff and is now soft-deleted.
    SOFT_DELETED,
    /// The user was already soft-deleted, so the delete had nothing to do; their recorded activity was not consulted.
    ALREADY_SOFT_DELETED,
    /// The user was last active at or after the cutoff, so they were left alone.
    STILL_ACTIVE,
    /// No user row exists.
    ABSENT
}
