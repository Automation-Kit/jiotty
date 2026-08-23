package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

/// Whether the model may answer in prose or must call one of the tools.
///
/// `any` is the one that changes a caller's design rather than its cost: with it there is no text path out of a turn, so a caller whose tools are its whole
/// output contract stops having to detect prose it cannot use — provided the tools cover every outcome, including "I cannot do this".
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonInclude(NON_ABSENT)
interface BaseToolChoice {
    /// `auto` lets the model choose between prose and a call, `any` requires some call, `tool` requires the one named in [#name].
    @Value.Parameter
    String type();

    /// The tool the model is forced to call; set only with type `tool`.
    Optional<String> name();
}
