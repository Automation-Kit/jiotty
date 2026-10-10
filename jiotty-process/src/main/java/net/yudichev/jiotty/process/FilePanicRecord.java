package net.yudichev.jiotty.process;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

/// A [PanicRecord] kept in one file, which exists only while a panic is recorded.
final class FilePanicRecord implements PanicRecord {
    private final Path file;

    FilePanicRecord(Path file) {
        this.file = checkNotNull(file);
    }

    @Override
    public void write(String reason) {
        try {
            Files.writeString(file, reason, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to record the panic in " + file, e);
        }
    }

    @Override
    public Optional<String> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the recorded panic from " + file, e);
        }
    }

    @Override
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clear the recorded panic in " + file, e);
        }
    }
}
