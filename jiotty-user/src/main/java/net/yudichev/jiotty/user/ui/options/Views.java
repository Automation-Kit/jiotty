package net.yudichev.jiotty.user.ui.options;

import com.fasterxml.jackson.annotation.JsonView;

/// Constants defining UI [JsonView]s.
public final class Views {
    private Views() {
    }

    /// View for UI serialization (SSE). Fields annotated with [Internal] are excluded.
    public static final class UI {
    }

    /// Fields in this view are only serialized when no view is active (e.g. persistence). They are excluded from the [UI] view. An example usage would be
    /// sensitive data that should not be sent to the UI.
    public static final class Internal {
    }
}
