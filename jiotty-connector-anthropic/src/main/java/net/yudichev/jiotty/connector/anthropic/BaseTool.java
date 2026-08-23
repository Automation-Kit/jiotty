package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

/// One tool offered to the model. Tools are declared per request and sit in the cacheable prefix ahead of the system blocks, so a caller that varies them
/// between calls pays for the whole prefix uncached.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonInclude(NON_ABSENT)
interface BaseTool {
    /// The name the model calls, and the one it quotes back in [ContentBlock#name()].
    String name();

    /// What the tool does and when to reach for it. The model chooses from this text alone, so it is prompt material, not documentation: say what the tool
    /// returns and what it costs, not how it is implemented.
    String description();

    @JsonProperty("input_schema")
    ToolInputSchema inputSchema();
}
