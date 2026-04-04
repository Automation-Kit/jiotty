package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkNotNull;
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
    private final String selectAllSql;
    private final SchedulingExecutor executor;
    private final ConcurrentMap<String, Object> cache = new ConcurrentHashMap<>();

    public SqlVarStoreOperations(CloseableDataSource dataSource, SchedulingExecutor executor, String userId,
                                 String upsertSql, String deleteSql, String selectAllSql) {
        this.dataSource = checkNotNull(dataSource);
        this.executor = checkNotNull(executor);
        this.userId = checkNotNull(userId);
        this.upsertSql = checkNotNull(upsertSql);
        this.deleteSql = checkNotNull(deleteSql);
        this.selectAllSql = checkNotNull(selectAllSql);
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
        var oldValue = cache.put(key, value);
        if (Objects.equals(oldValue, value)) {
            logger.debug("[{}] Skip persisting {} as it's unchanged", userId, key);
        } else {
            executor.execute(() -> asUnchecked(() -> {
                var now = Timestamp.from(Instant.now());
                try (var connection = dataSource.getConnection();
                     var statement = connection.prepareStatement(upsertSql)) {
                    String jsonValue = getAsUnchecked(() -> OBJECT_MAPPER.writeValueAsString(value));
                    statement.setString(1, userId);
                    statement.setString(2, key);
                    statement.setString(3, jsonValue);
                    statement.setTimestamp(4, now);
                    statement.setTimestamp(5, now);
                    statement.setTimestamp(6, now);
                    statement.executeUpdate();
                }
                logger.debug("[{}] Saved {}", userId, key);
            }));
        }
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

    @SuppressWarnings("unchecked")
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return Optional.ofNullable((T) cache.computeIfPresent(key, (_, v) -> {
            if (v instanceof Json(var json)) {
                return getAsUnchecked(() -> {
                    JavaType javaType = OBJECT_MAPPER.constructType(type.getType());
                    return OBJECT_MAPPER.readerFor(javaType).readValue(json);
                });
            }
            return v;
        }));
    }

    private record Json(String json) {}
}
