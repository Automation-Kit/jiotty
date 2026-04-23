package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.reflect.TypeToken;
import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
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

abstract class BaseFileVarStore implements VarStore {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new GuavaModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    protected final Logger logger = LogManager.getLogger(getClass());

    private final Path storeFile;
    private final Path storeFileTmp;
    private final Lock lock = new ReentrantLock();
    private final @Nullable VarStoreEncryption encryption;

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
        String plaintextJson = MoreThrowables.getAsUnchecked(() -> OBJECT_MAPPER.writeValueAsString(value));
        String envelope = enc.encrypt("", key, plaintextJson);
        updateConfig(configNode -> configNode.set(key, TextNode.valueOf(envelope)));
    }

    @Override
    public void clearValue(String key) {
        updateConfig(configNode -> configNode.remove(key));
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return inLock(lock, () -> MoreThrowables.getAsUnchecked(() -> {
            ObjectNode configNode = readConfig();

            JavaType javaType = OBJECT_MAPPER.constructType(type.getType());
            return Optional.ofNullable(configNode.get(key))
                           .map(valueNode -> MoreThrowables.getAsUnchecked(() -> OBJECT_MAPPER.readerFor(javaType).readValue(valueNode)));
        }));
    }

    @Override
    public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
        VarStoreEncryption enc = requireEncryption();
        return inLock(lock, () -> MoreThrowables.getAsUnchecked(() -> {
            ObjectNode configNode = readConfig();
            JsonNode valueNode = configNode.get(key);
            if (valueNode == null) {
                return Optional.empty();
            }
            JavaType javaType = OBJECT_MAPPER.constructType(type.getType());
            if (valueNode.isTextual() && VarStoreEncryption.isEnvelope(valueNode.textValue())) {
                String plaintextJson = enc.decrypt("", key, valueNode.textValue());
                return Optional.of(OBJECT_MAPPER.readerFor(javaType).readValue(plaintextJson));
            }
            logger.info("legacy plaintext value for '{}' — re-encrypting on read", key);
            T decoded = OBJECT_MAPPER.readerFor(javaType).readValue(valueNode);
            String plaintextJson = OBJECT_MAPPER.writeValueAsString(valueNode);
            configNode.set(key, TextNode.valueOf(enc.encrypt("", key, plaintextJson)));
            writeConfigLocked(configNode);
            return Optional.of(decoded);
        }));
    }

    private VarStoreEncryption requireEncryption() {
        checkState(encryption != null, "VarStore not configured with encryption");
        return encryption;
    }

    private void updateConfig(Consumer<ObjectNode> updater) {
        inLock(lock, () -> MoreThrowables.asUnchecked(() -> {
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
