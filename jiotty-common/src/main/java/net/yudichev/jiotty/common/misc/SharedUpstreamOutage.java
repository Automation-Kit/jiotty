package net.yudichev.jiotty.common.misc;

import net.yudichev.jiotty.common.rest.HttpResponseException;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;

import static com.google.common.base.Throwables.getCausalChain;

/// Classifies a failed call to a shared upstream as either an outage every caller would hit (a server-side 5xx, a network error, a database that is
/// unreachable or out of connections) or a problem specific to the calling subject (rejected credentials, a request the upstream considers invalid, data that
/// violates a constraint). Every component that splits its failure handling along that line reads this one verdict.
///
/// The causal chain is walked outermost-first and the first classifiable element decides:
/// - [HttpResponseException] — a shared outage when the status is a 5xx, or a 429: a throttle on the shared key/egress limits every caller at once. Any
/// other 4xx is a verdict on this specific request.
/// - a connectivity/resource [SQLException] ([SQLNonTransientConnectionException], [SQLRecoverableException], [SQLTransientException] — whose
/// [SQLTransientConnectionException] subtype is also what a pool reports when it has no connection to give) — the database is unreachable or out of
/// capacity for everyone: a shared outage. Any other [SQLException] (constraint violation, syntax, bad data) is about this caller's own query.
/// - any other [IOException] — a network-level failure on the shared egress path: a shared outage.
///
/// An unclassifiable chain is subject-specific: a subject-attributed alert for a shared problem is the recoverable mistake (operators see N similar alerts),
/// whereas a shared alert for a subject-specific problem hides which subject needs attention.
public final class SharedUpstreamOutage {

    private SharedUpstreamOutage() {
    }

    public static boolean indicatesSharedOutage(Throwable throwable) {
        for (Throwable cause : getCausalChain(throwable)) {
            switch (cause) {
                case HttpResponseException httpResponseException -> {
                    return httpResponseException.statusCode() >= 500 || httpResponseException.statusCode() == 429;
                }
                case SQLNonTransientConnectionException _,
                     SQLRecoverableException _,
                     SQLTransientException _,
                     IOException _ -> {
                    return true;
                }
                default -> {
                    // this link carries no verdict; the next one may
                }
            }
        }
        return false;
    }
}
