package net.yudichev.jiotty.common.misc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

public final class JvmWideIdGenerators {
    private static final ConcurrentMap<String, LongSupplier> GENERATORS_BY_OWNER = new ConcurrentHashMap<>();

    private JvmWideIdGenerators() {
    }

    /// @return an ID that is unique within this JVM. Must not be used to generate IDs that live beyond the scope of the current JVM, such as persistence ///
    /// IDs.
    public static LongSupplier generatorFor(String owner) {
        return GENERATORS_BY_OWNER.computeIfAbsent(owner, _ -> new LongSupplier() {
            private long nextId;

            @Override
            public long getAsLong() {
                return ++nextId;
            }
        });
    }
}
