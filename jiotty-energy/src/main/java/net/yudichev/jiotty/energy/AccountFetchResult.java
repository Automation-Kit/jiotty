package net.yudichev.jiotty.energy;

import static com.google.common.base.Preconditions.checkNotNull;

/// The outcome of an attempt to obtain an account's details. There is no "pending" variant: a subscriber receives nothing until a result is available, so the
/// first delivery is always a terminal [Loaded] or [Failed].
public sealed interface AccountFetchResult {
    /// The most recent attempt succeeded.
    ///
    /// @param account the obtained account details
    record Loaded(OctopusAccountDetails account) implements AccountFetchResult {
        public Loaded {
            checkNotNull(account);
        }
    }

    /// The most recent attempt failed.
    ///
    /// @param cause the failure that ended the most recent attempt
    record Failed(Throwable cause) implements AccountFetchResult {
        public Failed {
            checkNotNull(cause);
        }
    }
}
