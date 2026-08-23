package net.yudichev.jiotty.connector.anthropic;

import net.yudichev.jiotty.common.lang.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.yudichev.jiotty.connector.anthropic.MessagesResponses.textOf;
import static org.assertj.core.api.Assertions.assertThat;

/// Pins the exact JSON Anthropic's Messages API expects and returns. The rest of this connector is thin plumbing; this shape is the part that silently breaks
/// production if a property name, a nesting level, or an absent-field rendering drifts, and it cannot be caught by the compiler.
final class MessagesWireFormatTest {
    @Test
    void requestSerialisesToTheDocumentedShape() {
        var request = MessagesRequest.builder()
                                     .setModel("claude-haiku-4-5")
                                     .setMaxTokens(512)
                                     .addSystemBlocks(SystemBlock.builder()
                                                                 .setText("stable knowledge base")
                                                                 .setCacheControl(CacheControl.of("ephemeral"))
                                                                 .build())
                                     .addSystemBlocks(SystemBlock.builder().setText("per-caller context").build())
                                     .addMessages(Messages.createUserText("how do I set a charge target?"))
                                     .setTemperature(0.0)
                                     .build();

        assertThat(Json.parse(Json.stringify(request))).isEqualTo(Json.parse("""
                                                                             {
                                                                               "model": "claude-haiku-4-5",
                                                                               "max_tokens": 512,
                                                                               "system": [
                                                                                 {
                                                                                   "type": "text",
                                                                                   "text": "stable knowledge base",
                                                                                   "cache_control": {"type": "ephemeral"}
                                                                                 },
                                                                                 {"type": "text", "text": "per-caller context"}
                                                                               ],
                                                                               "messages": [{
                                                                                 "role": "user",
                                                                                 "content": [{"type": "text", "text": "how do I set a charge target?"}]
                                                                               }],
                                                                               "temperature": 0.0
                                                                             }"""));
    }

    /// The tool half of the request: Anthropic reads the argument schema as JSON Schema, so a misplaced nesting level or a dropped `required` entry leaves the
    /// model guessing at arguments the server then fails to parse.
    @Test
    void toolDefinitionsSerialiseAsJsonSchema() {
        var request = MessagesRequest.builder()
                                     .setModel("claude-haiku-4-5")
                                     .setMaxTokens(512)
                                     .addSystemBlocks(SystemBlock.builder().setText("s").build())
                                     .addMessages(Messages.createUserText("q"))
                                     .addTools(Tool.builder()
                                                   .setName("answer_question")
                                                   .setDescription("Deliver the answer.")
                                                   .setInputSchema(ToolInputSchema.builder()
                                                                                  .putProperties("answer", ToolProperty.of("string", "what to say"))
                                                                                  .putProperties("citedTopicIds", createStringArrayProperty("what it drew on"))
                                                                                  .addRequired("answer", "citedTopicIds")
                                                                                  .build())
                                                   .build())
                                     .build();

        assertThat(Json.parse(Json.stringify(request)).get("tools")).isEqualTo(Json.parse("""
                                                                                          [{
                                                                                            "name": "answer_question",
                                                                                            "description": "Deliver the answer.",
                                                                                            "input_schema": {
                                                                                              "type": "object",
                                                                                              "properties": {
                                                                                                "answer": {
                                                                                                  "type": "string",
                                                                                                  "description": "what to say"
                                                                                                },
                                                                                                "citedTopicIds": {
                                                                                                  "type": "array",
                                                                                                  "description": "what it drew on",
                                                                                                  "items": {"type": "string"}
                                                                                                }
                                                                                              },
                                                                                              "required": ["answer", "citedTopicIds"]
                                                                                            }
                                                                                          }]"""));
    }

    /// `tool_choice: any` is what removes the prose path from a turn, so its exact shape decides whether a caller's tools are its whole output contract or
    /// merely its preferred one.
    @Test
    void toolChoiceSerialisesToTheDocumentedShape() {
        var request = MessagesRequest.builder()
                                     .setModel("claude-haiku-4-5")
                                     .setMaxTokens(16)
                                     .addSystemBlocks(SystemBlock.builder().setText("s").build())
                                     .addMessages(Messages.createUserText("q"))
                                     .addTools(Tool.builder()
                                                   .setName("answer_question")
                                                   .setDescription("d")
                                                   .setInputSchema(ToolInputSchema.builder().build())
                                                   .build())
                                     .setToolChoice(ToolChoice.of("any"))
                                     .build();

        assertThat(Json.parse(Json.stringify(request)).get("tool_choice")).isEqualTo(Json.parse("{\"type\": \"any\"}"));
    }

    /// The turn a caller sends back after running the calls: the assistant's `tool_use` blocks replayed verbatim, then one `tool_result` per call, matched on
    /// the call id. Anthropic rejects the request outright if a call goes unanswered or an id does not line up, so this shape is load-bearing.
    @Test
    void toolResultTurnQuotesTheCallItAnswers() {
        var toolUse = Json.parse("""
                                 {"type": "tool_use", "id": "toolu_01", "name": "get_help_topic", "input": {"path": "charging/prices"}}""",
                                 ContentBlock.class);

        var conversation = List.of(Message.of(Role.ASSISTANT, List.of(toolUse)),
                                   Message.of(Role.USER, List.of(Messages.createToolResult("toolu_01", "Prices are published daily."),
                                                                 Messages.createToolError("toolu_02", "no such topic"))));

        assertThat(Json.parse(Json.stringify(conversation))).isEqualTo(Json.parse("""
                                                                                  [
                                                                                    {
                                                                                      "role": "assistant",
                                                                                      "content": [{
                                                                                        "type": "tool_use",
                                                                                        "id": "toolu_01",
                                                                                        "name": "get_help_topic",
                                                                                        "input": {"path": "charging/prices"}
                                                                                      }]
                                                                                    },
                                                                                    {
                                                                                      "role": "user",
                                                                                      "content": [
                                                                                        {
                                                                                          "type": "tool_result",
                                                                                          "tool_use_id": "toolu_01",
                                                                                          "content": "Prices are published daily."
                                                                                        },
                                                                                        {
                                                                                          "type": "tool_result",
                                                                                          "tool_use_id": "toolu_02",
                                                                                          "content": "no such topic",
                                                                                          "is_error": true
                                                                                        }
                                                                                      ]
                                                                                    }
                                                                                  ]"""));
    }

    /// A reply asking for tools is a *complete* reply that happens to end in a request, not a truncated one — and its calls must come back in the order the
    /// model made them, since that is the order it reasons in.
    @Test
    void toolUseReplyIsRecognisedAndItsCallsAreReadable() {
        var response = Json.parse("""
                                  {"content": [{"type": "text", "text": "Let me look."},
                                               {"type": "tool_use", "id": "toolu_01", "name": "get_help_topic", "input": {"path": "charging/prices"}},
                                               {"type": "tool_use", "id": "toolu_02", "name": "get_help_topic", "input": {"path": "charging/the-chart"}}],
                                   "stop_reason": "tool_use", "usage": {"input_tokens": 10, "output_tokens": 20}}""", MessagesResponse.class);

        assertThat(response.isAwaitingToolResults()).isTrue();
        assertThat(response.isCompleteTurn()).isFalse();
        assertThat(response.toolUses()).satisfiesExactly(first -> {
            assertThat(first.name()).contains("get_help_topic");
            assertThat(first.id()).contains("toolu_01");
            assertThat(first.input()).hasValueSatisfying(input -> assertThat(Json.convert(input, TopicRequest.class).path()).isEqualTo("charging/prices"));
        }, second -> {
            assertThat(second.name()).contains("get_help_topic");
            assertThat(second.id()).contains("toolu_02");
        });
    }

    /// An ordinary reply carries no calls, so a caller need not check the stop reason before iterating them.
    @Test
    void aReplyWithoutToolCallsHasNone() {
        var response = Json.parse("""
                                  {"content": [{"type": "text", "text": "answer"}], "stop_reason": "end_turn"}""", MessagesResponse.class);

        assertThat(response.toolUses()).isEmpty();
        assertThat(response.isAwaitingToolResults()).isFalse();
    }

    private static ToolProperty createStringArrayProperty(String description) {
        return ToolProperty.builder()
                           .setType("array")
                           .setDescription(description)
                           .setItems(ToolProperty.builder().setType("string").build())
                           .build();
    }

    /// An absent `temperature` must vanish from the payload rather than serialise as `null`, and a system block without a cache breakpoint must not emit an
    /// empty `cache_control` — Anthropic rejects both.
    @Test
    void absentOptionalsAreOmittedRatherThanNulled() {
        var request = MessagesRequest.builder()
                                     .setModel("claude-haiku-4-5")
                                     .setMaxTokens(16)
                                     .addSystemBlocks(SystemBlock.builder().setText("s").build())
                                     .addMessages(Messages.createUserText("q"))
                                     .build();

        assertThat(Json.stringify(request)).doesNotContain("temperature").doesNotContain("cache_control").doesNotContain("tools")
                                           .doesNotContain("tool_choice");
    }

    /// The assistant role is how a caller replays what the model said — and, in a tool conversation, what it asked for; it must round-trip as `assistant`.
    @Test
    void theAssistantRoleSerialisesAsAssistant() {
        assertThat(Json.stringify(Message.of(Role.ASSISTANT, List.of())))
                .isEqualTo("{\"role\":\"assistant\",\"content\":[]}");
    }

    @Test
    void responseParsesTokenCountsAndText() {
        var response = Json.parse("""
                                  {
                                    "id": "msg_01",
                                    "type": "message",
                                    "role": "assistant",
                                    "model": "claude-haiku-4-5",
                                    "content": [{"type": "text", "text": "Open the charging card."}],
                                    "stop_reason": "end_turn",
                                    "stop_sequence": null,
                                    "usage": {
                                      "input_tokens": 12,
                                      "cache_creation_input_tokens": 3400,
                                      "cache_read_input_tokens": 8100,
                                      "output_tokens": 25
                                    }
                                  }""", MessagesResponse.class);

        assertThat(textOf(response)).isEqualTo("Open the charging card.");
        assertThat(response.isCompleteTurn()).isTrue();
        assertThat(response.usage().inputTokens()).isEqualTo(12L);
        assertThat(response.usage().cacheCreationInputTokens()).isEqualTo(3400L);
        assertThat(response.usage().cacheReadInputTokens()).isEqualTo(8100L);
        assertThat(response.usage().outputTokens()).isEqualTo(25L);
    }

    /// A reply cut short by the token ceiling is the case callers must not parse-and-trust: its text is whole-looking but arbitrarily truncated.
    @Test
    void truncatedReplyIsNotACompleteTurn() {
        var response = Json.parse("""
                                  {"content": [{"type": "text", "text": "Open the char"}], "stop_reason": "max_tokens",
                                   "usage": {"input_tokens": 12, "output_tokens": 512}}""", MessagesResponse.class);

        assertThat(response.isCompleteTurn()).isFalse();
        assertThat(textOf(response)).isEqualTo("Open the char");
    }

    /// Blocks of a type this connector does not model must not break parsing, and must contribute no text.
    @Test
    void unmodelledContentBlocksParseWithoutText() {
        var response = Json.parse("""
                                  {"content": [{"type": "thinking", "thinking": "hmm"}, {"type": "text", "text": "answer"}],
                                   "stop_reason": "end_turn", "usage": {"input_tokens": 1, "output_tokens": 2}}""", MessagesResponse.class);

        assertThat(textOf(response)).isEqualTo("answer");
        assertThat(response.textLength()).isEqualTo("answer".length());
    }

    /// The length must match what appending actually produces, since callers size their buffer from it and a short answer would make them regrow anyway.
    @Test
    void textLengthCountsEveryTextBlock() {
        var response = Json.parse("""
                                  {"content": [{"type": "text", "text": "one "}, {"type": "text", "text": "two"}], "stop_reason": "end_turn"}""",
                                  MessagesResponse.class);

        assertThat(response.textLength()).isEqualTo(textOf(response).length()).isEqualTo(7);
    }

    /// A reply missing `usage` entirely must still parse — metering a zero is correct, throwing on the user's question is not.
    @Test
    void missingUsageDefaultsToZeroes() {
        var response = Json.parse("""
                                  {"content": [{"type": "text", "text": "a"}], "stop_reason": "end_turn"}""", MessagesResponse.class);

        assertThat(response.usage().inputTokens()).isZero();
        assertThat(response.usage().outputTokens()).isZero();
    }

    /// Every reply that omits `usage` shares one zero-valued instance rather than building its own.
    @Test
    void repliesMissingUsageShareOneInstance() {
        var first = Json.parse("""
                               {"content": [], "stop_reason": "end_turn"}""", MessagesResponse.class);
        var second = Json.parse("""
                                {"content": [], "stop_reason": "end_turn"}""", MessagesResponse.class);

        assertThat(first.usage()).isSameAs(second.usage());
    }

    /// Stands in for a caller's own argument type, since binding the arguments to one is the whole point of leaving them as a tree.
    private record TopicRequest(String path) {}
}
