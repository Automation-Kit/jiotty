package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.reflect.TypeToken;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

public final class InMemoryVarStore implements VarStore {
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new GuavaModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, String> serialisedValuesByKey = new ConcurrentHashMap<>();

    @Override
    public void saveValue(String key, Object value) {
        serialisedValuesByKey.put(key, getAsUnchecked(() -> mapper.writeValueAsString(value)));
    }

    @Override
    public void clearValue(String key) {
        serialisedValuesByKey.remove(key);
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        JavaType javaType = mapper.constructType(type.getType());
        return Optional.ofNullable(serialisedValuesByKey.get(key))
                       .map(encodedValue -> getAsUnchecked(() -> mapper.readerFor(javaType).readValue(encodedValue)));
    }

    @Override
    public VarStore forUser(String userId) {
        Utils.validateUserId(userId);
        return new UserScopedVarStore(this, userId + '.');
    }
}
