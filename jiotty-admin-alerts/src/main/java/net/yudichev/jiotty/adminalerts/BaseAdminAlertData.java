package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Map;

/// Specification for raising or re-raising an admin alert.
///
/// [#title()], [#severity()], and [#labels()] form the alert's identity (see [#key()]); [#description()] is per-event — each
/// [AdminAlertService#raise(AdminAlertData)] with the same key appends a new event carrying its own description rather than overwriting any earlier one.
@Value.Immutable
@PublicImmutablesStyle
public interface BaseAdminAlertData {
    String title();

    String description();

    AdminAlertSeverity severity();

    @Value.Default
    default Map<String, String> labels() {
        return Map.of();
    }

    /// Identity of the alert. Two raises with equal [#key()] target the same alert bundle. Always derived from [#title()], [#severity()], and [#labels()] via
    /// [AdminAlertKeys#derive(String,AdminAlertSeverity,Map)] — there is no setter.
    @Value.Derived
    default String key() {
        return AdminAlertKeys.derive(title(), severity(), labels());
    }
}
