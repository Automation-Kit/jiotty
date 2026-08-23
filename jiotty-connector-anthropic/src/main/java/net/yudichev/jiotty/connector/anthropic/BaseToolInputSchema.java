package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.List;
import java.util.Map;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

/// A tool's argument schema. Anthropic takes JSON Schema here; only the flat object-of-named-arguments shape is modelled, which is what a tool call is —
/// nesting an object inside an argument would be modelling a payload, not a call.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonInclude(NON_ABSENT)
interface BaseToolInputSchema {
    /// The only schema type a tool's arguments can take; Anthropic requires it to be stated.
    @Value.Derived
    default String type() {
        return "object";
    }

    Map<String, ToolProperty> properties();

    /// Which arguments the model must supply. An argument left out of this is one the model may omit, so the caller has to handle its absence — say so in the
    /// argument's description as well, since that is what the model actually reads.
    List<String> required();
}
