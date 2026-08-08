package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

/// One conversation turn. Only the plain-text content form is modelled — this connector has no use for image, document, or tool blocks.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseMessage {
    @Value.Parameter
    Role role();

    @Value.Parameter
    String content();
}
