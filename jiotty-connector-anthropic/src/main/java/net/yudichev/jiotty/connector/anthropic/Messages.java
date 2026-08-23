package net.yudichev.jiotty.connector.anthropic;

import com.google.common.collect.ImmutableList;

/// Factories for the conversation pieces a caller assembles by hand. The generated immutables cover every field the API has; these cover the two or three
/// combinations of them a turn actually takes.
public final class Messages {
    private Messages() {
    }

    /// The ordinary turn: the user says something.
    public static Message createUserText(String text) {
        return Message.of(Role.USER, ImmutableList.of(ContentBlock.builder().setType("text").setText(text).build()));
    }

    /// What a tool returned, addressed to the call that asked for it.
    ///
    /// @param toolUseId the [ContentBlock#id()] of the `tool_use` block being answered
    /// @param content   what the tool produced, for the model to read
    public static ContentBlock createToolResult(String toolUseId, String content) {
        return newToolResultBuilder(toolUseId, content).build();
    }

    /// Reports that a tool could not do what was asked. Distinct from returning prose that says so, which the model would read as the tool's answer.
    ///
    /// @param toolUseId the [ContentBlock#id()] of the `tool_use` block being answered
    /// @param message   what went wrong, and what the model should do instead — its only cue for what to try next
    public static ContentBlock createToolError(String toolUseId, String message) {
        return newToolResultBuilder(toolUseId, message).setError(true).build();
    }

    private static ContentBlock.Builder newToolResultBuilder(String toolUseId, String content) {
        return ContentBlock.builder().setType("tool_result").setToolUseId(toolUseId).setContent(content);
    }
}
