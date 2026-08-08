package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonProperty;

/// Who authored a turn in an Anthropic Messages conversation. The wire form is lower case, which the [JsonProperty] annotations supply in both directions.
public enum Role {
    @JsonProperty("user")
    USER,
    /// Also the role of a *prefill* — a trailing assistant turn the caller supplies to constrain how the model starts its reply.
    @JsonProperty("assistant")
    ASSISTANT
}
