package net.yudichev.jiotty.connector.anthropic;

/// Test-side helpers for asserting on a [MessagesResponse].
final class MessagesResponses {
    private MessagesResponses() {
    }

    /// Collects [MessagesResponse#appendText] into a [String] so a test can assert on the whole reply in one expression. Production callers append straight
    /// into the buffer they are already filling; a test has no such buffer, and the assertion reads better against a [String].
    static String textOf(MessagesResponse response) {
        var text = new StringBuilder(response.textLength());
        response.appendText(text);
        return text.toString();
    }
}
