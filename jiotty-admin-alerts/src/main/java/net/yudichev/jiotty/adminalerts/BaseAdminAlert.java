package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/// A persisted admin alert bundle. [#resolvedAt()], [#resolvedBy()], [#resolutionNote()] are absent for active bundles and present for resolved ones.
///
/// [#eventCount()] is the cumulative count of [AdminAlertService#raise(AdminAlertData)] calls that landed on this bundle. The bundle's retained event history
/// is bounded by the [AdminAlertServiceModule.MaxEventsPerBundle] cap (sliding window — oldest evicted), so [#eventCount()] can exceed the number of events
/// actually retained for this bundle.
@Value.Immutable
@PublicImmutablesStyle
public interface BaseAdminAlert {
    String id();

    String key();

    String title();

    AdminAlertSeverity severity();

    Map<String, String> labels();

    Instant firstSeenAt();

    Instant lastSeenAt();

    int eventCount();

    Optional<Instant> resolvedAt();

    Optional<String> resolvedBy();

    Optional<String> resolutionNote();
}
