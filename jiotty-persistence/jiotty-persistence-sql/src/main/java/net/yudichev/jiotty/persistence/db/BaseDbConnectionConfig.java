package net.yudichev.jiotty.persistence.db;

import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

@Immutable
@PublicImmutablesStyle
interface BaseDbConnectionConfig {
    String host();

    String dbName();

    String username();

    int port();

    BindingSpec<String> passwordSpec();
}
