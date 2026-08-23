package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

/// One argument of a tool, as JSON Schema describes it.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonInclude(NON_ABSENT)
interface BaseToolProperty {
    /// The JSON type of the argument — `string`, `integer`, `boolean`, `array`.
    @Value.Parameter
    String type();

    /// What the argument means. The model has nothing else to go on, so this is where a constraint the schema cannot express (one of the ids listed in the
    /// system prompt, a bound on how many to ask for) has to be stated.
    @Value.Parameter
    Optional<String> description();

    /// What an `array` argument holds. Absent for every other type.
    Optional<ToolProperty> items();
}
