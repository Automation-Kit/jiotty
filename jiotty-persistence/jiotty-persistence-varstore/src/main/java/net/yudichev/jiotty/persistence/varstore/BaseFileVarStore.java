package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.reflect.TypeToken;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.move;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static net.yudichev.jiotty.common.lang.Locks.inLock;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

abstract class BaseFileVarStore implements PrefixClearableVarStore {
    private static final ObjectMapper OBJECT_MAPPER = VarStoreJson.INDENTED;

    protected final Logger logger = LogManager.getLogger(getClass());

    private final Path storeFile;
    private final Path storeFileTmp;
    private final Lock lock = new ReentrantLock();
    private final @Nullable VarStoreEncryption encryption;
    /// TEMPORARY — delete with [LegacyTemporalFormatBackfill]. Holds the keys this store has already brought up to date, so each key is compared once.
    private final Set<String> backfilledKeys = new HashSet<>();

    BaseFileVarStore(Path storeFile, @Nullable VarStoreEncryption encryption) {
        this.storeFile = checkNotNull(storeFile, "storeFile");
        logger.info("Using store file {}", this.storeFile.toAbsolutePath());
        storeFileTmp = this.storeFile.resolveSibling("data.tmp");
        this.encryption = encryption;
    }

    @Override
    public void saveValue(String key, Object value) {
        updateConfig(configNode -> configNode.set(key, OBJECT_MAPPER.valueToTree(value)));
    }

    @Override
    public void saveValueEncrypted(String key, Object value) {
        VarStoreEncryption enc = requireEncryption();
        String plaintextJson = getAsUnchecked(() -> OBJECT_MAPPER.writeValueAsString(value));
        String envelope = enc.encrypt("", key, plaintextJson);
        updateConfig(configNode -> configNode.set(key, TextNode.valueOf(envelope)));
    }

    @Override
    public void clearValue(String key) {
        updateConfig(configNode -> configNode.remove(key));
    }

    @Override
    public void clearAll() {
        updateConfig(ObjectNode::removeAll);
    }

    @Override
    public void clearAllWithPrefix(String keyPrefix) {
        updateConfig(configNode -> {
            var toRemove = new ArrayList<String>();
            configNode.fieldNames().forEachRemaining(name -> {
                if (name.startsWith(keyPrefix)) {
                    toRemove.add(name);
                }
            });
            configNode.remove(toRemove);
        });
    }

    @Override
    public List<ExportedEntry> exportEntries() {
        return exportEntriesWithPrefix("");
    }

    @Override
    public List<ExportedEntry> exportEntriesWithPrefix(String keyPrefix) {
        return inLock(lock, () -> getAsUnchecked(() -> {
            ObjectNode configNode = readConfig();
            var entries = new ArrayList<ExportedEntry>();
            configNode.fieldNames().forEachRemaining(name -> {
                if (name.startsWith(keyPrefix)) {
                    JsonNode value = configNode.get(name);
                    // A value stored encrypted at rest is a secret — report it redacted, without decrypting; everything else exports as its stored JSON.
                    entries.add(value.isTextual() && VarStoreEncryption.isEnvelope(value.textValue())
                                ? new ExportedEntry(name.substring(keyPrefix.length()), true, null)
                                : new ExportedEntry(name.substring(keyPrefix.length()), false, value.toString()));
                }
            });
            return entries;
        }));
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return inLock(lock, () -> getAsUnchecked(() -> {
            ObjectNode configNode = readConfig();

            JavaType javaType = OBJECT_MAPPER.constructType(type.getType());
            return Optional.ofNullable(configNode.get(key))
                           .map(valueNode -> {
                               T value = getAsUnchecked(() -> OBJECT_MAPPER.readerFor(javaType).readValue(valueNode));
                               rewriteIfStoredFormIsStale(configNode, key, valueNode, value);
                               return value;
                           });
        }));
    }

    /// TEMPORARY — delete with [LegacyTemporalFormatBackfill], along with [#backfilledKeys]. Writes the canonical form into `configNode`, which this store has
    /// already parsed and holds the lock over, so the rewrite costs one file write.
    private void rewriteIfStoredFormIsStale(ObjectNode configNode, String key, JsonNode valueNode, Object value) {
        if (!backfilledKeys.add(key) || !LegacyTemporalFormatBackfill.storedFormIsStale(OBJECT_MAPPER, valueNode.toString(), value)) {
            return;
        }
        LegacyTemporalFormatBackfill.logRewrite(key);
        try {
            configNode.set(key, OBJECT_MAPPER.valueToTree(value));
            writeConfigLocked(configNode);
        } catch (IOException e) {
            // A read that found its value succeeds whatever the rewrite does; the next read retries it.
            backfilledKeys.remove(key);
            LegacyTemporalFormatBackfill.logRewriteFailure(key, e);
        }
    }

    @Override
    public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
        VarStoreEncryption enc = requireEncryption();
        JsonNode valueNode = inLock(lock, () -> getAsUnchecked(() -> readConfig().get(key)));
        if (valueNode == null) {
            return Optional.empty();
        }
        checkState(valueNode.isTextual() && VarStoreEncryption.isEnvelope(valueNode.textValue()),
                   "value under '%s' read via readValueEncrypted is not an encryption envelope", key);
        String plaintextJson = enc.decrypt("", key, valueNode.textValue());
        JavaType javaType = OBJECT_MAPPER.constructType(type.getType());
        return Optional.of(getAsUnchecked(() -> OBJECT_MAPPER.readerFor(javaType).readValue(plaintextJson)));
    }

    private VarStoreEncryption requireEncryption() {
        checkState(encryption != null, "VarStore not configured with encryption");
        return encryption;
    }

    private void updateConfig(Consumer<ObjectNode> updater) {
        inLock(lock, () -> asUnchecked(() -> {
            ObjectNode configNode = readConfig();

            updater.accept(configNode);
            writeConfigLocked(configNode);
        }));
    }

    private void writeConfigLocked(ObjectNode configNode) throws IOException {
        OBJECT_MAPPER.writeValue(storeFileTmp.toFile(), configNode);
        move(storeFileTmp, storeFile, REPLACE_EXISTING);
    }

    private ObjectNode readConfig() throws IOException {
        if (!isRegularFile(storeFile)) {
            createDirectories(storeFile.getParent());
            createFile(storeFile);
        }

        ObjectNode configNode;
        byte[] contents = readAllBytes(storeFile);
        if (contents.length > 0) {
            configNode = OBJECT_MAPPER.readValue(contents, ObjectNode.class);
        } else {
            configNode = OBJECT_MAPPER.createObjectNode();
        }

        return configNode;
    }
}
