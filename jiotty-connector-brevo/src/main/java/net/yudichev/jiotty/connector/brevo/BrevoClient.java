package net.yudichev.jiotty.connector.brevo;

import java.util.concurrent.CompletableFuture;

/// Minimal client for Brevo's transactional email API. A send carries one message to one recipient — no templates held at Brevo, no contact lists, no
/// campaign features: the caller supplies fully-rendered subject and body, so the wording lives in the calling repository rather than in a vendor console.
public interface BrevoClient {
    /// Sends `email`, completing once Brevo has accepted it for delivery — not that it was delivered, since a bounce arrives later and is not observed here.
    ///
    /// **Delivery is at-least-once**, so a caller for whom a duplicate is worse than a miss must deduplicate above this interface.
    CompletableFuture<Void> sendEmail(BrevoEmail email);

    /// Reads one day of aggregate transactional-email statistics and discards them, completing with whether Brevo accepted the key. An accepted call counts
    /// as use under Brevo's rule that deactivates a key unused for 90 days, and the read is within the permissions of a key restricted to transactional email.
    CompletableFuture<AccessCheckResult> checkAccess();

    /// The outcome of [#checkAccess()] when Brevo answered it.
    enum AccessCheckResult {
        ACCEPTED,
        /// Brevo refused the key: it is deactivated, its permissions exclude transactional email, or the calling host's IP is not authorised.
        KEY_REJECTED
    }
}
