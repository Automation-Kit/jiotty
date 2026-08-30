package net.yudichev.jiotty.connector.brevo;

import java.util.concurrent.CompletableFuture;

/// Minimal client for Brevo's transactional email API. One call sends one message to one recipient — no templates held at Brevo, no contact lists, no
/// campaign features: the caller supplies fully-rendered subject and body, so the wording lives in the calling repository rather than in a vendor console.
public interface BrevoClient {
    /// Sends `email`, completing once Brevo has accepted it for delivery — not that it was delivered, since a bounce arrives later and is not observed here.
    ///
    /// **Delivery is at-least-once**, so a caller for whom a duplicate is worse than a miss must deduplicate above this interface.
    CompletableFuture<Void> sendEmail(BrevoEmail email);
}
