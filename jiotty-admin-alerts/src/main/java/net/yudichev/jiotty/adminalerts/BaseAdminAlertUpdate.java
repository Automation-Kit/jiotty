package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.Map;
import java.util.Optional;

/// Partial PATCH for an existing active admin alert. Missing fields stay unchanged.
@Value.Immutable
@PublicImmutablesStyle
public interface BaseAdminAlertUpdate {
    Optional<String> description();

    Optional<Map<String, String>> labels();
}
