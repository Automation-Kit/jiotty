package net.yudichev.jiotty.timeseriescache;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Declares the schema version of a value type stored in a [TimeSeriesCache] stream. Each cached value carries the version it was written under; when read back
/// under a different current version, the value is treated as absent and recomputed rather than reinterpreted. An unannotated type is version `1`.
///
/// Bump [#value] whenever the type's serialized shape changes in a way that an older value cannot satisfy — a renamed/removed/retyped field, or any other
/// change that would make an old value deserialize to a wrong or incomplete result. This annotation is the single source of truth for "what shape is the
/// current code expecting"; bumping it makes existing cached values of the old shape recompute on next read instead of decoding incorrectly.
@Retention(RUNTIME)
@Target(TYPE)
public @interface CacheSchemaVersion {
    /// The current schema version of the annotated type. Must be in `[1, 65535]`.
    int value();
}
