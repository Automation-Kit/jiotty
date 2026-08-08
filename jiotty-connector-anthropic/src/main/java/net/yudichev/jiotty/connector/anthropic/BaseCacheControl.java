package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

/// Cache directive attached to a [SystemBlock].
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
interface BaseCacheControl {
    /// The only cache type Anthropic offers; entries live for a few minutes of inactivity.
    @Value.Parameter
    String type();
}
