package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;

import static com.google.common.base.Preconditions.checkNotNull;

/// What is known of the Octopus account.
sealed interface AccountState {
    /// The account as one fetch returned it. Each successful fetch makes a new instance, so two instances are the same object only if they come from the
    /// same fetch.
    /// Its [#toString()] names the state and not the account, whose meter points carry the customer's supply identifiers.
    record Loaded(OctopusAccountData account) implements AccountState {
        public Loaded {
            checkNotNull(account);
        }

        @Override
        public String toString() {
            return "Loaded";
        }
    }

    /// No fetch of the account has succeeded.
    ///
    /// @param cause the failure of the latest fetch
    record Unavailable(Throwable cause) implements AccountState {
        public Unavailable {
            checkNotNull(cause);
        }
    }
}
