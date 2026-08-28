package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// In-memory [VarStore] test double.
public final class InMemoryVarStore implements PrefixClearableVarStore {
    private static final ObjectMapper mapper = VarStoreJson.INDENTED;

    private final Map<String, String> serialisedValuesByKey = new ConcurrentHashMap<>();

    @Override
    public void saveValue(String key, Object value) {
        serialisedValuesByKey.put(key, getAsUnchecked(() -> mapper.writeValueAsString(value)));
    }

    @Override
    public void saveValueEncrypted(String key, Object value) {
        String plaintext = getAsUnchecked(() -> mapper.writeValueAsString(value));
        serialisedValuesByKey.put(key, VarStoreEncryption.ENVELOPE_PREFIX + plaintext);
    }

    @Override
    public void clearValue(String key) {
        serialisedValuesByKey.remove(key);
    }

    @Override
    public void clearAll() {
        serialisedValuesByKey.clear();
    }

    @Override
    public void clearAllWithPrefix(String keyPrefix) {
        serialisedValuesByKey.keySet().removeIf(key -> key.startsWith(keyPrefix));
    }

    @Override
    public List<ExportedEntry> exportEntries() {
        return exportEntriesWithPrefix("");
    }

    @Override
    public List<ExportedEntry> exportEntriesWithPrefix(String keyPrefix) {
        var entries = new ArrayList<ExportedEntry>();
        serialisedValuesByKey.forEach((key, stored) -> {
            if (key.startsWith(keyPrefix)) {
                String scopedKey = key.substring(keyPrefix.length());
                entries.add(VarStoreEncryption.isEnvelope(stored)
                            ? new ExportedEntry(scopedKey, true, null)
                            : new ExportedEntry(scopedKey, false, stored));
            }
        });
        return entries;
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        JavaType javaType = mapper.constructType(type.getType());
        return Optional.ofNullable(serialisedValuesByKey.get(key))
                       .map(encodedValue -> getAsUnchecked(() -> mapper.readerFor(javaType).readValue(encodedValue)));
    }

    @Override
    public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
        JavaType javaType = mapper.constructType(type.getType());
        String stored = serialisedValuesByKey.get(key);
        if (stored == null) {
            return Optional.empty();
        }
        checkState(VarStoreEncryption.isEnvelope(stored),
                   "value under '%s' read via readValueEncrypted is not an encryption envelope", key);
        String plaintext = stored.substring(VarStoreEncryption.ENVELOPE_PREFIX.length());
        return Optional.of(getAsUnchecked(() -> mapper.readerFor(javaType).readValue(plaintext)));
    }

    @Override
    public VarStore forUser(String userId) {
        Utils.validateUserId(userId);
        return new UserScopedVarStore(this, userId + '.');
    }

    public Set<String> allKeys() {
        return Collections.unmodifiableSet(serialisedValuesByKey.keySet());
    }

    public Optional<String> rawStoredValue(String key) {
        return Optional.ofNullable(serialisedValuesByKey.get(key));
    }
}
