package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Optional;

/// One block of a response. Blocks of a type this connector does not model (thinking, tool use) still parse — they simply carry no [#text].
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseContentBlock {
    String type();

    Optional<String> text();
}
