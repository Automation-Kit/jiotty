package net.yudichev.jiotty.connector.google.calendar;

/// Error reasons from Google's Calendar API error responses, for the mock responses the tests build.
final class GoogleErrorReasons {
    /// The reason of an unauthorised response, as for a revoked grant.
    static final String AUTH_ERROR_REASON = "authError";
    /// The reason of a forbidden response confined to one event, which leaves the credential valid.
    static final String FORBIDDEN_FOR_NON_ORGANIZER_REASON = "forbiddenForNonOrganizer";

    private GoogleErrorReasons() {
    }
}
