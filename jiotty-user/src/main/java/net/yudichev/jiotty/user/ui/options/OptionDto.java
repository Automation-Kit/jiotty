package net.yudichev.jiotty.user.ui.options;

/// Plain data record for [Option]s to be serialized to JSON and rendered on the client.
public interface OptionDto {
    String type();

    String key();

    String label();

    String tabName();

    int order();
}
