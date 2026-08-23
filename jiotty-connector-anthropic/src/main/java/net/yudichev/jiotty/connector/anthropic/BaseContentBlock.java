package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

/// One block of a message, in either direction. Anthropic's block types are a union discriminated by [#type] — this models the three a tool-using caller
/// deals with (`text`, `tool_use`, `tool_result`), so every other field is optional and which ones are populated follows from the type.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonDeserialize
@JsonInclude(NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseContentBlock {
    String type();

    /// The text of a `text` block.
    Optional<String> text();

    /// A `tool_use` block's call id, which the matching [#toolUseId] must quote back. Absent only on a malformed reply.
    Optional<String> id();

    /// Which tool a `tool_use` block is calling.
    Optional<String> name();

    /// A `tool_use` block's arguments. Left as a tree because its shape is whatever the called tool's `input_schema` declared, which this connector does not
    /// know: the caller binds it to a type of its own with [Json#convert], and a call replayed into the next request goes back exactly as the model made it.
    Optional<JsonNode> input();

    /// The [#id] of the `tool_use` this `tool_result` block answers. Anthropic requires a result for every call in the assistant turn being replayed, matched
    /// on this.
    @JsonProperty("tool_use_id")
    Optional<String> toolUseId();

    /// A `tool_result` block's payload — what the tool returned, for the model to read.
    Optional<String> content();

    /// Whether a `tool_result` reports a failure rather than a result. The model is expected to react to it (retry differently, give up) rather than treat it
    /// as data, so a tool that could not do what was asked says so here instead of returning prose that reads like an answer.
    @JsonProperty("is_error")
    Optional<Boolean> isError();

    /// Whether this is a call the model is asking the caller to run. Ignored by Jackson: unlike the response types, a block is serialised as well as parsed,
    /// and a derived convenience emitted onto the wire is a field Anthropic never sent and does not accept back.
    @JsonIgnore
    default boolean isToolUse() {
        return "tool_use".equals(type());
    }
}
