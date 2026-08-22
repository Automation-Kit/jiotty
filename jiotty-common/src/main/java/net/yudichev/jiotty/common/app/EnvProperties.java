package net.yudichev.jiotty.common.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/// Loads a `.env.properties`-style file (Java-properties syntax) and resolves required keys with recursive `${NAME}` interpolation against the file then the
/// environment (so `${HOME}` works).
public final class EnvProperties {
    private static final Pattern VAR_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final Properties props;

    private EnvProperties(Properties props) {
        this.props = props;
    }

    /// Loads the file at `path`. Fails loudly if it is missing (the working directory must be the module dir so the relative path resolves).
    public static EnvProperties load(Path path) {
        checkState(Files.exists(path),
                   "%s not found — set the working directory to the car-server-local module dir, or copy the matching .example and edit it.",
                   path.toAbsolutePath());
        Properties props = new Properties();
        try (BufferedReader in = Files.newBufferedReader(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path.toAbsolutePath(), e);
        }
        return new EnvProperties(props);
    }

    /// The value for `key`, fully `${}`-interpolated. Fails if absent or blank.
    public String require(String key) {
        String raw = props.getProperty(key);
        checkNotNull(raw, "%s missing from the properties file", key);
        checkArgument(!raw.isBlank(), "%s is blank in the properties file", key);
        return interpolate(raw, 0);
    }

    /// The value for `key` interpolated, or `fallback` if the key is absent or blank.
    public String optional(String key, String fallback) {
        String raw = props.getProperty(key);
        return raw == null || raw.isBlank() ? fallback : interpolate(raw, 0);
    }

    private String interpolate(String value, int depth) {
        checkState(depth < 10, "Cyclic or excessively nested ${} reference at: %s", value);
        Matcher m = VAR_REF.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        while (m.find()) {
            String name = m.group(1);
            String replacement = props.getProperty(name);
            if (replacement == null) {
                // noinspection CallToSystemGetenv — fallback so paths can use ${HOME}/${USER}/etc.
                replacement = System.getenv(name);
            }
            checkNotNull(replacement, "Unresolved reference ${%s}", name);
            m.appendReplacement(out, Matcher.quoteReplacement(interpolate(replacement, depth + 1)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
