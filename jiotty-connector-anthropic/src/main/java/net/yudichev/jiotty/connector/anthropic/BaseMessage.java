package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.List;

/// One conversation turn. Content is a block array, since a turn carrying tool calls or tool results has no plain-string form;
/// [Messages#createUserText] builds the ordinary text turn.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseMessage {
    @Value.Parameter
    Role role();

    @Value.Parameter
    List<ContentBlock> content();
}
