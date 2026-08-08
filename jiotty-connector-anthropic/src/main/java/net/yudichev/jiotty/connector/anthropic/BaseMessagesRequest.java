package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.List;
import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;
import static com.google.common.base.Preconditions.checkState;

/// A `POST /v1/messages` payload. Only the subset of the API this connector needs is modelled: no tools, no streaming, no images.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonInclude(NON_ABSENT)
interface BaseMessagesRequest {
    String model();

    /// Hard ceiling on generated tokens. Anthropic requires it, and it doubles as the cost cap for a single call: a response that hits the ceiling comes back
    /// with `stop_reason` `max_tokens` rather than `end_turn`, which callers should treat as a truncated (therefore untrustworthy) answer.
    @JsonProperty("max_tokens")
    int maxTokens();

    /// System prompt blocks, in order. Put the long stable prefix first and mark it with [SystemBlock#cacheControl()]; put per-caller material after it.
    @JsonProperty("system")
    List<SystemBlock> systemBlocks();

    List<Message> messages();

    /// Absent leaves the model's default. `0.0` makes sampling as deterministic as the API allows, which is what classification-style calls want.
    Optional<Double> temperature();

    @Value.Check
    default void validate() {
        checkState(maxTokens() > 0, "maxTokens must be positive: %s", maxTokens());
        checkState(!messages().isEmpty(), "at least one message is required");
    }
}
