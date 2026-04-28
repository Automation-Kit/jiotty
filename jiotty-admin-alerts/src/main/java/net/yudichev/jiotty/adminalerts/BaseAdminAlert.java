package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/// A persisted admin alert row. [#resolvedAt], [#resolvedBy], [#resolutionNote] are absent for active alerts and present for resolved ones.
@Value.Immutable
@PublicImmutablesStyle
public interface BaseAdminAlert {
    String id();

    String dedupKey();

    String title();

    String description();

    AdminAlertSeverity severity();

    Map<String, String> labels();

    Instant firstSeenAt();

    Instant lastSeenAt();

    int updateCount();

    Optional<Instant> resolvedAt();

    Optional<String> resolvedBy();

    Optional<String> resolutionNote();
}
