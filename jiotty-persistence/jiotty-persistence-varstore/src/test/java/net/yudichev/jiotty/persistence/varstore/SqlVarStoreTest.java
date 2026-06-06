package net.yudichev.jiotty.persistence.varstore;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.async.ExecutorFactoryImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlVarStoreTest {
    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    @Mock
    private VarStoreEncryption encryption;

    private SqlVarStore varStore;
    private Optional<Path> legacyPath = Optional.empty();
    private boolean singleUser;
    private Optional<VarStoreEncryption> configuredEncryption = Optional.empty();

    @AfterEach
    void tearDown() {
        if (varStore != null) {
            varStore.stop();
        }
    }

    @Test
    void savesAndReadsValue() {
        startVarStore();
        varStore.saveValue("key1", "hello");
        flushExecutor();

        assertThat(varStore.readValue(String.class, "key1")).contains("hello");
    }

    @Test
    void saveOverwritesExistingValue() {
        startVarStore();
        varStore.saveValue("key1", "first");
        flushExecutor();
        varStore.saveValue("key1", "second");
        flushExecutor();

        assertThat(varStore.readValue(String.class, "key1")).contains("second");
    }

    @Test
    void clearValueRemovesExistingValue() {
        startVarStore();
        varStore.saveValue("key1", "hello");
        flushExecutor();

        varStore.clearValue("key1");
        flushExecutor();

        varStore.stop();
        startVarStore();

        assertThat(varStore.readValue(String.class, "key1")).isEmpty();
    }

    @Test
    void readMissingKeyReturnsEmpty() {
        startVarStore();
        assertThat(varStore.readValue(String.class, "nonexistent")).isEmpty();
    }

    @Test
    void multiUserScopingIsIndependent() {
        startVarStore();
        VarStore alice = varStore.forUser("alice");
        VarStore bob = varStore.forUser("bob");

        alice.saveValue("colour", "red");
        bob.saveValue("colour", "blue");
        flushExecutor();

        assertThat(alice.readValue(String.class, "colour")).contains("red");
        assertThat(bob.readValue(String.class, "colour")).contains("blue");
        assertThat(varStore.readValue(String.class, "colour")).isEmpty();
    }

    @Test
    void clearValueOnScopedStoreDoesNotAffectOtherUsers() {
        startVarStore();
        VarStore alice = varStore.forUser("alice");
        VarStore bob = varStore.forUser("bob");

        alice.saveValue("colour", "red");
        bob.saveValue("colour", "blue");
        flushExecutor();

        alice.clearValue("colour");
        flushExecutor();

        assertThat(alice.readValue(String.class, "colour")).isEmpty();
        assertThat(bob.readValue(String.class, "colour")).contains("blue");
    }

    @Test
    void clearAllOnScopedStoreRemovesAllUserKeysButLeavesOtherUsers() {
        startVarStore();
        VarStore alice = varStore.forUser("alice");
        VarStore bob = varStore.forUser("bob");

        alice.saveValue("colour", "red");
        alice.saveValue("shape", "square");
        bob.saveValue("colour", "blue");
        flushExecutor();

        alice.clearAll();
        flushExecutor();

        varStore.stop();
        startVarStore();

        VarStore aliceReloaded = varStore.forUser("alice");
        assertThat(aliceReloaded.readValue(String.class, "colour")).isEmpty();
        assertThat(aliceReloaded.readValue(String.class, "shape")).isEmpty();
        assertThat(varStore.forUser("bob").readValue(String.class, "colour")).contains("blue");
    }

    @Test
    void clearAllOnUnscopedMultiUserStoreThrows() {
        startVarStore();
        assertThatThrownBy(() -> varStore.clearAll())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearAllOnSingleUserStoreRemovesAllValues() {
        singleUser = true;
        startVarStore();
        varStore.saveValue("key1", "hello");
        varStore.saveValue("key2", "world");
        flushExecutor();

        varStore.clearAll();
        flushExecutor();

        varStore.stop();
        startVarStore();

        assertThat(varStore.readValue(String.class, "key1")).isEmpty();
        assertThat(varStore.readValue(String.class, "key2")).isEmpty();
    }

    @Test
    void singleUserForUserReturnsThis() {
        singleUser = true;
        startVarStore();

        VarStore scoped = varStore.forUser("");
        assertThat(scoped).isSameAs(varStore);
    }

    @Test
    void singleUserRejectsNonEmptyUserId() {
        singleUser = true;
        startVarStore();

        assertThatThrownBy(() -> varStore.forUser("someone"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forUserOnAlreadyScopedStoreThrows() {
        startVarStore();
        VarStore scoped = varStore.forUser("alice");

        assertThatThrownBy(() -> scoped.forUser("bob"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void userIdWithDotRejected() {
        startVarStore();
        assertThatThrownBy(() -> varStore.forUser("user.name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dots");
    }

    @Test
    void saveOnStoppedStoreThrows() {
        startVarStore();
        varStore.stop();
        assertThatThrownBy(() -> varStore.saveValue("key", "value"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readOnStoppedStoreThrows() {
        startVarStore();
        varStore.stop();
        assertThatThrownBy(() -> varStore.readValue(String.class, "key"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void complexTypesWithTypeToken() {
        startVarStore();
        varStore.saveValue("list", List.of("a", "b", "c"));
        varStore.saveValue("map", Map.of("x", 1, "y", 2));
        flushExecutor();

        assertThat(varStore.readValue(new TypeToken<List<String>>() {}, "list")).contains(List.of("a", "b", "c"));
        assertThat(varStore.readValue(new TypeToken<Map<String, Integer>>() {}, "map")).contains(Map.of("x", 1, "y", 2));
    }

    @Test
    void timestampsAreSet() {
        startVarStore();
        varStore.saveValue("ts_key", "value");
        flushExecutor();

        var timestamps = readTimestamps();
        assertThat(timestamps.createTime()).isNotNull();
        assertThat(timestamps.updateTime()).isNotNull();
        assertThat(timestamps.createTime()).isEqualTo(timestamps.updateTime());
    }

    @Test
    void updateTimestampChangesOnOverwrite() {
        startVarStore();
        varStore.saveValue("ts_key", "first");
        flushExecutor();

        var firstTimestamps = readTimestamps();

        // small delay to ensure timestamp difference
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        varStore.saveValue("ts_key", "second");
        flushExecutor();

        var secondTimestamps = readTimestamps();
        assertThat(secondTimestamps.createTime()).isEqualTo(firstTimestamps.createTime());
        assertThat(secondTimestamps.updateTime()).isAfter(firstTimestamps.updateTime());
    }

    @Test
    void migrationFromFile(@TempDir Path tempDir) {
        Path filePath = tempDir.resolve("data.json");
        legacyPath = Optional.of(filePath);
        writeViaFileVarStore(filePath, Map.of("key1", "value1", "key2", 42));

        startVarStore();

        assertThat(varStore.readValue(String.class, "key1")).contains("value1");
        assertThat(varStore.readValue(Integer.class, "key2")).contains(42);
        assertThat(filePath).doesNotExist();
        assertThat(tempDir.resolve("data.json.moved-to-database")).exists();
    }

    @Test
    void migrationIsIdempotent(@TempDir Path tempDir) {
        Path filePath = tempDir.resolve("data.json");
        legacyPath = Optional.of(filePath);
        writeViaFileVarStore(filePath, Map.of("key1", "value1"));

        startVarStore();

        assertThat(varStore.readValue(String.class, "key1")).contains("value1");

        // second migration should be a no-op (file already renamed)
        varStore.stop();
        startVarStore();
        assertThat(varStore.readValue(String.class, "key1")).contains("value1");
    }

    @Test
    void migrationWithNoFile(@TempDir Path tempDir) {
        Path filePath = tempDir.resolve("nonexistent.json");
        legacyPath = Optional.of(filePath);

        // should not throw
        startVarStore();
    }

    @Test
    void encryptedSaveInvokesEncryptAndStoresResult() {
        configuredEncryption = Optional.of(encryption);
        when(encryption.encrypt(eq(""), eq("token"), any())).thenReturn("ENC1$stubbed-ciphertext");
        startVarStore();

        varStore.saveValueEncrypted("token", "super-secret");
        flushExecutor();

        assertThat(readRawValue("", "token")).isEqualTo("ENC1$stubbed-ciphertext");
        verify(encryption).encrypt("", "token", "\"super-secret\"");
    }

    @Test
    void encryptedReadInvokesDecryptOnEnvelope() {
        configuredEncryption = Optional.of(encryption);
        when(encryption.decrypt("", "token", "ENC1$stored-envelope")).thenReturn("\"decrypted-value\"");
        startVarStore();
        seedRawRowAndReload("", "token", "ENC1$stored-envelope");

        assertThat(varStore.readValueEncrypted(String.class, "token")).contains("decrypted-value");
        verify(encryption).decrypt("", "token", "ENC1$stored-envelope");
    }

    @Test
    void encryptedReadRewritesLegacyPlaintextRow() {
        configuredEncryption = Optional.of(encryption);
        when(encryption.encrypt(eq(""), eq("legacy"), any())).thenReturn("ENC1$re-encrypted");
        startVarStore();
        seedRawRowAndReload("", "legacy", "\"legacy-plaintext\"");

        assertThat(varStore.readValueEncrypted(String.class, "legacy")).contains("legacy-plaintext");
        flushExecutor();

        assertThat(readRawValue("", "legacy")).isEqualTo("ENC1$re-encrypted");
        verify(encryption).encrypt("", "legacy", "\"legacy-plaintext\"");
    }

    @Test
    void encryptedMethodsThrowWhenEncryptionNotConfigured() {
        startVarStore();

        assertThatThrownBy(() -> varStore.saveValueEncrypted("k", "v"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured with encryption");
        assertThatThrownBy(() -> varStore.readValueEncrypted(String.class, "k"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured with encryption");
    }

    @Test
    void encryptedPerUserScopedStorePassesUserIdToEncryption() {
        configuredEncryption = Optional.of(encryption);
        when(encryption.encrypt(eq("alice"), eq("token"), any())).thenReturn("ENC1$alice");
        when(encryption.encrypt(eq("bob"), eq("token"), any())).thenReturn("ENC1$bob");
        startVarStore();

        varStore.forUser("alice").saveValueEncrypted("token", "alice-secret");
        varStore.forUser("bob").saveValueEncrypted("token", "bob-secret");
        flushExecutor();

        assertThat(readRawValue("alice", "token")).isEqualTo("ENC1$alice");
        assertThat(readRawValue("bob", "token")).isEqualTo("ENC1$bob");
        verify(encryption).encrypt("alice", "token", "\"alice-secret\"");
        verify(encryption).encrypt("bob", "token", "\"bob-secret\"");
    }

    private void startVarStore() {
        varStore = new SqlVarStore(postgres.dataSourceFactory(), new ExecutorFactoryImpl(), "var_store", singleUser, legacyPath, configuredEncryption);
        varStore.start();
    }

    /// Inserts a row directly via JDBC and restarts the var store so its cache picks up the seeded
    /// value. Requires the var store to be running beforehand so the table already exists.
    private void seedRawRowAndReload(String userId, String key, String rawValue) {
        insertRawValue(userId, key, rawValue);
        varStore.stop();
        startVarStore();
    }

    private void flushExecutor() {
        getAsUnchecked(() -> varStore.executor().submit(() -> {}).get(10, SECONDS));
    }

    private static void writeViaFileVarStore(Path filePath, Map<String, Object> data) {
        var fileVarStore = new MultiUserFileVarStore(filePath, null);
        data.forEach(fileVarStore::saveValue);
    }

    private static String readRawValue(String userId, String key) {
        return getAsUnchecked(() -> {
            try (var connection = postgres.dataSource().getConnection();
                 var statement = connection.prepareStatement("SELECT value FROM var_store WHERE user_id = ? AND key = ?")) {
                statement.setString(1, userId);
                statement.setString(2, key);
                try (var rs = statement.executeQuery()) {
                    assertThat(rs.next()).as("expected a row for user '%s' key '%s'", userId, key).isTrue();
                    return rs.getString(1);
                }
            }
        });
    }

    private static void insertRawValue(String userId, String key, String jsonValue) {
        getAsUnchecked(() -> {
            try (var connection = postgres.dataSource().getConnection();
                 var statement = connection.prepareStatement(
                         "INSERT INTO var_store (user_id, key, value, create_time, update_time) VALUES (?, ?, ?, ?, ?)"
                         + " ON CONFLICT (user_id, key) DO UPDATE SET value = EXCLUDED.value")) {
                var now = Timestamp.from(Instant.now());
                statement.setString(1, userId);
                statement.setString(2, key);
                statement.setString(3, jsonValue);
                statement.setTimestamp(4, now);
                statement.setTimestamp(5, now);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static Timestamps readTimestamps() {
        return getAsUnchecked(() -> {
            try (var connection = postgres.dataSource().getConnection();
                 var statement = connection.prepareStatement(
                         "SELECT create_time, update_time FROM var_store WHERE user_id = '' AND key = 'ts_key'")) {
                try (ResultSet rs = statement.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    return new Timestamps(rs.getTimestamp(1), rs.getTimestamp(2));
                }
            }
        });
    }

    private record Timestamps(Timestamp createTime, Timestamp updateTime) {}
}
