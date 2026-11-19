package net.yudichev.jiotty.energy.octopus;

import static com.google.common.base.Preconditions.checkNotNull;

/// The outcome of an attempt to obtain an account's details. There is no "pending" variant: a subscriber receives nothing until a result is available, so the
/// first delivery is always a terminal [Loaded] or [Failed].
public sealed interface AccountFetchResult {
    /// An attempt obtained the account details; a later failed attempt leaves them in place.
    ///
    /// @param account the obtained account details
    record Loaded(OctopusAccountDetails account) implements AccountFetchResult {
        public Loaded {
            checkNotNull(account);
        }
    }

    /// An attempt failed before any account details had been obtained.
    ///
    /// @param cause the failure that ended that attempt
    record Failed(Throwable cause) implements AccountFetchResult {
        public Failed {
            checkNotNull(cause);
        }
    }
}
