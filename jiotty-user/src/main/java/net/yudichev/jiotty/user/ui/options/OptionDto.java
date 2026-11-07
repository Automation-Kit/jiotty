package net.yudichev.jiotty.user.ui.options;

import java.util.Set;

/// Plain data record for [Option]s to be serialized to JSON and rendered on the client.
public interface OptionDto {
    String type();

    String key();

    String label();

    String tabName();

    int order();

    /// The option's [BaseOptionMeta#tags()].
    Set<String> tags();
}
