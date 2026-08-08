package net.yudichev.jiotty.connector.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_ABSENT;

/// One block of the system prompt. The system prompt is sent as an array rather than a bare string so that a long, stable prefix can be marked cacheable
/// independently of the blocks that follow it — see [#cacheControl].
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonInclude(NON_ABSENT)
interface BaseSystemBlock {
    /// The only block type this connector emits; Anthropic requires it on every block.
    @Value.Derived
    default String type() {
        return "text";
    }

    String text();

    /// Marks this block — and every block before it — as a cache breakpoint, so a later request repeating the identical prefix is billed at the much lower
    /// cache-read rate. Anthropic keys the cache on the **prefix bytes**, not on any session or account identity, so blocks shared verbatim across callers
    /// share one cache entry. Anything caller-specific must therefore go in a *later* block, or it invalidates the entry for everyone.
    @JsonProperty("cache_control")
    Optional<CacheControl> cacheControl();
}
