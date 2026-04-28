package net.yudichev.jiotty.adminalerts;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Specification for raising or re-raising an admin alert.
///
/// @param labels   carries arbitrary tags.
/// @param dedupKey application-defined identity used to coalesce repeated firings of the same condition; it is unique among active alerts
public record AdminAlertData(String dedupKey,
                             String title,
                             String description,
                             AdminAlertSeverity severity,
                             Map<String, String> labels) {
    public AdminAlertData {
        checkNotNull(dedupKey, "dedupKey");
        checkArgument(!dedupKey.isBlank(), "dedupKey must not be blank");
        checkNotNull(title, "title");
        checkArgument(!title.isBlank(), "title must not be blank");
        checkNotNull(description, "description");
        checkNotNull(severity, "severity");
        labels = ImmutableMap.copyOf(checkNotNull(labels, "labels"));
    }
}
