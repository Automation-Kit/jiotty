package net.yudichev.jiotty.security;

import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.util.concurrent.CompletableFuture;

/// Ends the grant behind a token an [OAuth2TokenManager] stored and that manager must no longer be running. One instance serves every var store holding a
/// token for the same API, client and scope.
public interface StoredGrantRevoker {
    /// Asks the authorisation server to end the grant behind the token stored in `varStore`, then deletes that token — unless a token stored since has
    /// replaced it, which is left in place, or the authorisation server could not be asked within the retry budget, in which case it is kept for a later call.
    ///
    /// @return the outcome; fails only when `varStore` itself fails
    CompletableFuture<Result> revoke(VarStore varStore);

    /// How a revocation ended.
    enum Result {
        /// The authorisation server ended the grant, or answered that it had already ended.
        ENDED,
        /// No token was stored, so there was no grant to end.
        NOTHING_STORED,
        /// The authorisation server refused to end the grant; the token is deleted, since asking again would be refused again.
        REFUSED,
        /// The authorisation server could not be reached, or kept answering with a server-side or rate-limit failure, until the retry budget was spent; the
        /// token is kept, so a later call tries again.
        UNREACHABLE
    }
}
