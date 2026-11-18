package net.yudichev.jiotty.connector.icloud.status;

import java.util.concurrent.CompletableFuture;

/// A source of the status that Apple publishes for the services on which reading an iCloud calendar depends.
public interface AppleSystemStatusSource {
    /// Reads the status once.
    ///
    /// @return the status as of the read, which is [IcloudCalendarStatus#UNREADABLE] when the read fails or its result cannot be interpreted
    CompletableFuture<IcloudCalendarStatus> readIcloudCalendarStatus();

    enum IcloudCalendarStatus {
        /// Apple reports an event that has started and has not ended.
        ONGOING_EVENT,
        /// Apple reports no event, or only events that have ended or are yet to start.
        NO_ONGOING_EVENT,
        UNREADABLE
    }
}
