package net.yudichev.jiotty.user.push;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStoreEncryption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class PushDeviceStoreImplTest {
    private static final Instant START = Instant.parse("2026-04-15T10:00:00Z");
    private static final String TOKEN_A = "ExponentPushToken[aaa]";
    private static final String TOKEN_B = "ExponentPushToken[bbb]";

    private ProgrammableClock clock;
    private InMemoryVarStore varStore;
    private PushDeviceStoreImpl store;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc();
        clock.setTimeAndTick(START);
        varStore = new InMemoryVarStore();
        store = new PushDeviceStoreImpl(clock, varStore);
        store.start();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.stop();
        }
    }

    @Test
    void upsert_newDevice_appearsInList() {
        PushDeviceRecord record = recordFor("device-1", TOKEN_A);

        store.upsert(record);
        clock.tick();

        assertThat(listNow()).containsExactly(record);
    }

    @Test
    void upsert_sameDeviceId_replacesExistingRecord() {
        store.upsert(recordFor("device-1", TOKEN_A));
        PushDeviceRecord rotated = recordFor("device-1", TOKEN_B);
        store.upsert(rotated);
        clock.tick();

        assertThat(listNow()).containsExactly(rotated);
    }

    @Test
    void remove_existingDevice_removesIt() {
        store.upsert(recordFor("device-1", TOKEN_A));
        PushDeviceRecord survivor = recordFor("device-2", TOKEN_B);
        store.upsert(survivor);
        store.remove("device-1");
        clock.tick();

        assertThat(listNow()).containsExactly(survivor);
    }

    @Test
    void remove_absentDevice_leavesStoreUnchanged() {
        PushDeviceRecord existing = recordFor("device-1", TOKEN_A);
        store.upsert(existing);
        store.remove("nonexistent");
        clock.tick();

        assertThat(listNow()).containsExactly(existing);
    }

    @Test
    void pruneByToken_removesAllRecordsWithMatchingToken() {
        PushDeviceRecord keep = recordFor("device-1", TOKEN_A);
        store.upsert(keep);
        store.upsert(recordFor("device-2", TOKEN_B));
        store.upsert(recordFor("device-3", TOKEN_B));
        store.pruneByToken(TOKEN_B);
        clock.tick();

        assertThat(listNow()).containsExactly(keep);
    }

    @Test
    void pruneByToken_noMatch_leavesStoreUnchanged() {
        PushDeviceRecord record = recordFor("device-1", TOKEN_A);
        store.upsert(record);
        store.pruneByToken("ExponentPushToken[unknown]");
        clock.tick();

        assertThat(listNow()).containsExactly(record);
    }

    @Test
    void list_emptyStore_returnsEmptyList() {
        assertThat(listNow()).isEmpty();
    }

    @Test
    void remove_lastDevice_clearsVarStoreKey() {
        store.upsert(recordFor("device-1", TOKEN_A));
        clock.tick();
        assertThat(varStore.allKeys()).containsExactly("push.devices");
        // Push tokens are persisted encrypted at rest, not as plaintext JSON.
        assertThat(varStore.rawStoredValue("push.devices")).hasValueSatisfying(stored ->
                                                                                       assertThat(VarStoreEncryption.isEnvelope(stored)).as(
                                                                                               "push devices stored as encryption envelope").isTrue());

        store.remove("device-1");
        clock.tick();

        assertThat(varStore.allKeys()).isEmpty();
    }

    private List<PushDeviceRecord> listNow() {
        CompletableFuture<List<PushDeviceRecord>> future = store.list();
        clock.tick();
        return future.resultNow();
    }

    private static PushDeviceRecord recordFor(String deviceId, String token) {
        return PushDeviceRecord.builder()
                               .setDeviceId(deviceId)
                               .setToken(token)
                               .setRegisteredAt(START)
                               .build();
    }
}
