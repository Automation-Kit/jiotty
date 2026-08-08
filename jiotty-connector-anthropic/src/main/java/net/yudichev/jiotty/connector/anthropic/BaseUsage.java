package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

/// Token counts for one call. The four buckets are billed at different rates, so callers that meter spend must keep them apart rather than summing them:
/// cache reads are roughly a tenth of the ordinary input rate, cache writes are dearer than it, and output is dearer again.
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseUsage {
    /// Uncached input tokens — the part of the prompt that was neither read from nor written to the cache.
    @JsonProperty("input_tokens")
    @Value.Default
    default long inputTokens() {
        return 0L;
    }

    @JsonProperty("output_tokens")
    @Value.Default
    default long outputTokens() {
        return 0L;
    }

    /// Tokens written into the prompt cache by this call — charged at a premium, and only paid on the call that populates an entry.
    @JsonProperty("cache_creation_input_tokens")
    @Value.Default
    default long cacheCreationInputTokens() {
        return 0L;
    }

    /// Tokens served from the prompt cache instead of being re-processed. A healthy shared-prefix design keeps this far above [#inputTokens].
    @JsonProperty("cache_read_input_tokens")
    @Value.Default
    default long cacheReadInputTokens() {
        return 0L;
    }
}
