package net.yudichev.jiotty.connector.anthropic;

import net.yudichev.jiotty.common.lang.Json;
import org.junit.jupiter.api.Test;

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
                                     .addMessages(Message.of(Role.USER, "how do I set a charge target?"))
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
                                                                               "messages": [{"role": "user", "content": "how do I set a charge target?"}],
                                                                               "temperature": 0.0
                                                                             }"""));
    }

    /// An absent `temperature` must vanish from the payload rather than serialise as `null`, and a system block without a cache breakpoint must not emit an
    /// empty `cache_control` — Anthropic rejects both.
    @Test
    void absentOptionalsAreOmittedRatherThanNulled() {
        var request = MessagesRequest.builder()
                                     .setModel("claude-haiku-4-5")
                                     .setMaxTokens(16)
                                     .addSystemBlocks(SystemBlock.builder().setText("s").build())
                                     .addMessages(Message.of(Role.USER, "q"))
                                     .build();

        assertThat(Json.stringify(request)).doesNotContain("temperature").doesNotContain("cache_control");
    }

    /// The assistant role exists so a caller can *prefill* the reply; it must round-trip as `assistant`.
    @Test
    void assistantPrefillRoleSerialisesAsAssistant() {
        assertThat(Json.stringify(Message.of(Role.ASSISTANT, "{"))).isEqualTo("{\"role\":\"assistant\",\"content\":\"{\"}");
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
}
