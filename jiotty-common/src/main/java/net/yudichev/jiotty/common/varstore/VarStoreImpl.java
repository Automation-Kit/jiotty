package net.yudichev.jiotty.common.varstore;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createFile;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.move;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static net.yudichev.jiotty.common.lang.Locks.inLock;

public final class VarStoreImpl implements VarStore {
    private static final Logger logger = LoggerFactory.getLogger(VarStoreImpl.class);

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new GuavaModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path storeFile;
    private final Lock lock = new ReentrantLock();
    private final Path storeFileTmp;

    public VarStoreImpl(Path storeFile) {
        this.storeFile = checkNotNull(storeFile);
        storeFileTmp = this.storeFile.resolveSibling("data.tmp");
        logger.info("Using store file {}", storeFile.toAbsolutePath());
    }

    @Override
    public void saveValue(String key, Object value) {
        inLock(lock, () -> MoreThrowables.asUnchecked(() -> {
            ObjectNode configNode = readConfig();

            configNode.set(key, mapper.valueToTree(value));
            mapper.writeValue(storeFileTmp.toFile(), configNode);
            move(storeFileTmp, storeFile, REPLACE_EXISTING);
        }));
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return inLock(lock, () -> MoreThrowables.getAsUnchecked(() -> {
            ObjectNode configNode = readConfig();

            JavaType javaType = mapper.constructType(type.getType());
            return Optional.ofNullable(configNode.get(key))
                           .map(valueNode -> MoreThrowables.getAsUnchecked(() -> mapper.readerFor(javaType).readValue(valueNode)));
        }));
    }

    private ObjectNode readConfig() throws IOException {
        if (!isRegularFile(storeFile)) {
            createDirectories(storeFile.getParent());
            createFile(storeFile);
        }

        ObjectNode configNode;
        byte[] contents = readAllBytes(storeFile);
        if (contents.length > 0) {
            configNode = mapper.readValue(contents, ObjectNode.class);
        } else {
            configNode = mapper.createObjectNode();
        }

        return configNode;
    }
}
