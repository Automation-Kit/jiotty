package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Throwables;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

@SuppressWarnings("JDBCPrepareStatementWithNonConstantString") // all SQL is owned by this class and the table name is validated
final class SqlVarStoreOperations {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new GuavaModule());
    private final Logger logger = LogManager.getLogger(getClass());
    private final CloseableDataSource dataSource;
    private final String userId;
    private final String upsertSql;
    private final String deleteSql;
    private final String deleteAllSql;
    private final String selectAllSql;
    private final SchedulingExecutor executor;
    private final @Nullable VarStoreEncryption encryption;
    private final ConcurrentMap<String, Object> cache = new ConcurrentHashMap<>();

    public SqlVarStoreOperations(CloseableDataSource dataSource, SchedulingExecutor executor, String userId,
                                 String upsertSql, String deleteSql, String deleteAllSql, String selectAllSql,
                                 @Nullable VarStoreEncryption encryption) {
        this.dataSource = checkNotNull(dataSource);
        this.executor = checkNotNull(executor);
        this.userId = checkNotNull(userId);
        this.upsertSql = checkNotNull(upsertSql);
        this.deleteSql = checkNotNull(deleteSql);
        this.deleteAllSql = checkNotNull(deleteAllSql);
        this.selectAllSql = checkNotNull(selectAllSql);
        this.encryption = encryption;
    }

    public void loadAll() {
        getAsUnchecked(() -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(selectAllSql)) {
                statement.setString(1, userId);
                try (var rs = statement.executeQuery()) {
                    while (rs.next()) {
                        cache.put(rs.getString(1), new Json(rs.getString(2)));
                    }
                }
            }
            logger.debug("[{}] Loaded {} entries", userId, cache.size());
            return null;
        });
    }

    public void saveValue(String key, Object value) {
        persist(key, value, Function.identity());
    }

    public void saveValueEncrypted(String key, Object value) {
        VarStoreEncryption enc = requireEncryption();
        persist(key, value, plaintextJson -> enc.encrypt(userId, key, plaintextJson));
    }

    public void clearValue(String key) {
        Object oldValue = cache.remove(key);
        if (oldValue == null) {
            logger.debug("[{}] Skip clearing {} as it's absent", userId, key);
        } else {
            executor.execute(() -> asUnchecked(() -> {
                try (var connection = dataSource.getConnection();
                     var statement = connection.prepareStatement(deleteSql)) {
                    statement.setString(1, userId);
                    statement.setString(2, key);
                    statement.executeUpdate();
                }
                logger.debug("[{}] Cleared {}", userId, key);
            }));
        }
    }

    public void clearAll() {
        cache.clear();
        executor.execute(() -> asUnchecked(() -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(deleteAllSql)) {
                statement.setString(1, userId);
                int rows = statement.executeUpdate();
                logger.debug("[{}] Cleared all {} entries", userId, rows);
            }
        }));
    }

    /// Reads the raw stored form of every row in this scope straight from the DB (not the in-memory cache, which holds decrypted objects for keys read via
    /// [#readValueEncrypted]), classifying each value by its envelope sigil so secrets are reported redacted and never decrypted.
    public List<VarStore.ExportedEntry> exportEntries() {
        return getAsUnchecked(() -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(selectAllSql)) {
                statement.setString(1, userId);
                try (var rs = statement.executeQuery()) {
                    var entries = new ArrayList<VarStore.ExportedEntry>();
                    while (rs.next()) {
                        String key = rs.getString(1);
                        String storedValue = rs.getString(2);
                        entries.add(VarStoreEncryption.isEnvelope(storedValue)
                                    ? new VarStore.ExportedEntry(key, true, null)
                                    : new VarStore.ExportedEntry(key, false, storedValue));
                    }
                    return entries;
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return Optional.ofNullable((T) cache.computeIfPresent(key, (_, v) -> {
            if (v instanceof Json(var json)) {
                return deserialise(type, json);
            }
            return v;
        }));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
        VarStoreEncryption enc = requireEncryption();
        return Optional.ofNullable((T) cache.computeIfPresent(key, (k, v) -> {
            if (v instanceof Json(var json)) {
                checkState(VarStoreEncryption.isEnvelope(json),
                           "[%s] value under '%s' read via readValueEncrypted is not an encryption envelope", userId, k);
                return deserialiseSecret(type, k, enc.decrypt(userId, k, json));
            }
            return v;
        }));
    }

    private void persist(String key, Object value, Function<String, String> serialisedEncoder) {
        var oldValue = cache.put(key, value);
        if (Objects.equals(oldValue, value)) {
            logger.debug("[{}] Skip persisting {} as it's unchanged", userId, key);
            return;
        }
        scheduleWrite(key, value, serialisedEncoder);
    }

    private void scheduleWrite(String key, Object value, Function<String, String> serialisedEncoder) {
        executor.execute(() -> asUnchecked(() -> {
            var now = Timestamp.from(Instant.now());
            String storedValue = serialisedEncoder.apply(getAsUnchecked(() -> OBJECT_MAPPER.writeValueAsString(value)));
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(upsertSql)) {
                statement.setString(1, userId);
                statement.setString(2, key);
                statement.setString(3, storedValue);
                statement.setTimestamp(4, now);
                statement.setTimestamp(5, now);
                statement.setTimestamp(6, now);
                statement.executeUpdate();
            }
            logger.debug("[{}] Saved {}", userId, key);
        }));
    }

    private static Object deserialise(TypeToken<?> type, String json) {
        return getAsUnchecked(() -> {
            JavaType javaType = OBJECT_MAPPER.constructType(type.getType());
            return OBJECT_MAPPER.readerFor(javaType).readValue(json);
        });
    }

    /// Deserialises a decrypted value, replacing any failure with one that carries no part of the plaintext.
    ///
    /// Jackson quotes the offending content in its message — a token, a credential, whatever the caller chose to encrypt — and that message travels into
    /// log lines and admin-alert descriptions. A value stored through [#saveValueEncrypted] is encrypted at rest and reported as redacted by
    /// [VarStore#exportEntries] precisely so it never reaches either, and a shape it no longer parses as must not be the one hole in that. The replacement
    /// names the key, the expected type and the failure's class, which is what an operator needs to act, and drops the cause, whose message is the leak.
    private Object deserialiseSecret(TypeToken<?> type, String key, String json) {
        try {
            return deserialise(type, json);
        } catch (RuntimeException e) {
            // The root cause names the actual parse failure, and a class name carries none of the content — the wrapper this arrives in says only
            // "RuntimeException".
            throw new IllegalStateException("[" + userId + "] encrypted value under '" + key + "' could not be read as " + type + ": "
                                            + Throwables.getRootCause(e).getClass().getSimpleName()
                                            + " (its content is withheld, being encrypted at rest)");
        }
    }

    private VarStoreEncryption requireEncryption() {
        checkState(encryption != null, "VarStore not configured with encryption");
        return encryption;
    }

    private record Json(String json) {}
}
