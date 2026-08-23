package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;

/// A `POST /v1/messages` reply.
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseMessagesResponse {
    /// The all-zero counts a reply reports when it carries no `usage` object. Shared rather than built per reply: [Usage] is immutable and every such reply
    /// means the same thing, so a [Value.Default] body that called the builder would allocate one identical object for every reply that omits the field.
    Usage NO_USAGE = Usage.builder().build();

    List<ContentBlock> content();

    /// Why generation stopped. Absent only on malformed replies. Anything other than `end_turn` means the model did not finish its own sentence — most
    /// importantly `max_tokens`, where the text is cut mid-thought and any structure in it (JSON, a citation list) may be incomplete. Callers that parse the
    /// reply must therefore consult [#isCompleteTurn] *before* trusting the parse, not only when the parse fails.
    @JsonProperty("stop_reason")
    Optional<String> stopReason();

    @Value.Default
    default Usage usage() {
        return NO_USAGE;
    }

    /// Appends the text of every text block to `to` — the whole reply, for the single-block replies this connector's callers elicit. Appending rather than
    /// returning a [String] lets a caller assemble the reply straight into a buffer it is already filling, instead of building one [String] here for the
    /// caller to immediately copy again.
    default void appendText(Appendable to) {
        for (ContentBlock block : content()) {
            block.text().ifPresent(text -> Append.to(to, text));
        }
    }

    /// How many characters [#appendText] will append. A caller assembling the reply into a [StringBuilder] sizes it with this — a reply runs to hundreds or
    /// thousands of characters, so a buffer left at the default capacity would reallocate and copy its way up to that length several times over.
    default int textLength() {
        int length = 0;
        for (ContentBlock block : content()) {
            Optional<String> text = block.text();
            if (text.isPresent()) {
                length += text.get().length();
            }
        }
        return length;
    }

    /// Whether the model completed its turn of its own accord — the only outcome under which the reply is known to be whole.
    default boolean isCompleteTurn() {
        return stopReason().filter("end_turn"::equals).isPresent();
    }

    /// Whether the model stopped to call tools and is waiting for their results. This too is a whole reply — the model finished what it meant to say — so a
    /// caller checking only [#isCompleteTurn] would mistake every tool call for a truncation.
    default boolean isAwaitingToolResults() {
        return stopReason().filter("tool_use"::equals).isPresent();
    }

    /// Every `tool_use` block in the reply, in the order the model made them, whatever the stop reason — a reply cut short mid-call still carries the call,
    /// with arguments only [#isAwaitingToolResults] makes safe to trust.
    ///
    /// Every one of these must be answered — with a result or an error — in the turn the caller sends back, and the assistant turn replayed before it must
    /// carry these same blocks, or Anthropic rejects the next request.
    default List<ContentBlock> toolUses() {
        var toolUses = ImmutableList.<ContentBlock>builderWithExpectedSize(content().size());
        for (ContentBlock block : content()) {
            if (block.isToolUse()) {
                toolUses.add(block);
            }
        }
        return toolUses.build();
    }
}
