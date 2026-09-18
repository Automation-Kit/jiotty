package net.yudichev.jiotty.persistence.varstore;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class VarStoreAadTest {
    /// Pins the exact wire format, which every envelope already written depends on: a value stored under the old format cannot be decrypted under a new one.
    /// The empty user id is the file-backed store's, which has no user to bind to.
    @ParameterizedTest
    @CsvSource({"alice, token, 'jiotty-varstore-v1|alice|token'", "'', token, 'jiotty-varstore-v1||token'"})
    void bindsTagUserIdAndKeyInTheStoredWireFormat(String userId, String key, String expected) {
        assertThat(VarStoreAad.of(userId, key)).isEqualTo(expected);
    }

    @ParameterizedTest
    @MethodSource
    void differentRowsGetDifferentAad(String userIdA, String keyA, String userIdB, String keyB) {
        assertThat(VarStoreAad.of(userIdA, keyA)).isNotEqualTo(VarStoreAad.of(userIdB, keyB));
    }

    static Stream<Arguments> differentRowsGetDifferentAad() {
        return Stream.of(
                // userId differs
                arguments("alice", "token", "bob", "token"),
                // key differs
                arguments("alice", "tokenA", "alice", "tokenB"));
    }
}
