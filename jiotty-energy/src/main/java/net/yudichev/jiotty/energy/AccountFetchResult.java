package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;

/// The latest outcome of [OctopusAccountContext]'s account poll. Exactly one of the three states holds at any time: the first fetch has not completed yet, the
/// most recent fetch succeeded, or the most recent fetch (after any retries) failed.
public sealed interface AccountFetchResult {
    /// No fetch has completed yet — the value held before the first poll resolves.
    record Loading() implements AccountFetchResult {
    }

    /// The most recent poll fetched the account successfully.
    ///
    /// @param account the fetched account payload
    record Loaded(OctopusAccountData account) implements AccountFetchResult {
    }

    /// The most recent poll failed after exhausting retries.
    ///
    /// @param cause the failure that ended the most recent fetch attempt
    record Failed(Throwable cause) implements AccountFetchResult {
    }
}
