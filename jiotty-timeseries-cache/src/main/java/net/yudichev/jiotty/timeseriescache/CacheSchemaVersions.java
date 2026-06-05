package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;

import static com.google.common.base.Preconditions.checkArgument;

/// Resolves the [CacheSchemaVersion] of a cached value type. The single place that reads the annotation, so the requirement that every cached type declares a
/// version (no implicit default) and the valid range (`[1, 65535]`, bounded by the frame's unsigned 16-bit version field) are enforced once.
final class CacheSchemaVersions {
    static final int MIN_VERSION = 1;
    static final int MAX_VERSION = 0xFFFF;

    private CacheSchemaVersions() {
    }

    static int resolve(TypeToken<?> type) {
        CacheSchemaVersion annotation = type.getRawType().getAnnotation(CacheSchemaVersion.class);
        checkArgument(annotation != null,
                      "%s must declare a @CacheSchemaVersion (no implicit default) — annotate it, or define the stream with the explicit-version overload",
                      type);
        return checkVersion(annotation.value());
    }

    static int checkVersion(int version) {
        checkArgument(version >= MIN_VERSION && version <= MAX_VERSION, "schema version must be in [%s, %s], was %s", MIN_VERSION, MAX_VERSION, version);
        return version;
    }
}
