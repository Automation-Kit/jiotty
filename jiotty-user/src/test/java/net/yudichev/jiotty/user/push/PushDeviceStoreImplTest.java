package net.yudichev.jiotty.user.push;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.EnvelopeEncryption;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PushDeviceStoreImplTest {
    private static final Instant START = Instant.parse("2026-04-15T10:00:00Z");
    private static final String TOKEN_A = "ExponentPushToken[aaa]";
    private static final String TOKEN_B = "ExponentPushToken[bbb]";
    private static final String TOKEN_C = "ExponentPushToken[ccc]";
    private static final String REJECTION_MESSAGE = "queue full";
    /// The type that reads the stored records as plain JSON objects, keyed by device id.
    private static final TypeToken<Map<String, Map<String, Object>>> STORED_RECORDS_TYPE = new TypeToken<>() {};

    private ProgrammableClock clock;
    private InMemoryVarStore varStore;
    private SchedulingExecutor executor;
    private PushDeviceStoreImpl store;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc();
        clock.setTimeAndTick(START);
        varStore = new InMemoryVarStore();
        executor = clock.createSingleThreadedSchedulingExecutor(PushDeviceModule.EXECUTOR_THREAD_NAME);
        store = new PushDeviceStoreImpl(() -> executor, varStore);
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
    void upsert_sameTokenUnderNewDeviceId_keepsOnlyTheNewRecord() {
        store.upsert(recordFor("device-1", TOKEN_A));
        PushDeviceRecord reRegistered = recordFor("device-2", TOKEN_A);
        store.upsert(reRegistered);
        clock.tick();

        assertThat(listNow()).containsExactly(reRegistered);
    }

    @Test
    void upsert_sameTokenUnderNewDeviceId_leavesOtherDevicesAlone() {
        PushDeviceRecord otherDevice = recordFor("device-1", TOKEN_B);
        store.upsert(otherDevice);
        store.upsert(recordFor("device-2", TOKEN_A));
        PushDeviceRecord reRegistered = recordFor("device-3", TOKEN_A);
        store.upsert(reRegistered);
        clock.tick();

        assertThat(listNow()).containsExactlyInAnyOrder(otherDevice, reRegistered);
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

    /// Code with no running store reads the backing store alone, so what it reads must be what the store would list.
    @Test
    void listStored_readsTheBackingStoreWithoutTheComponent() {
        PushDeviceRecord record = recordFor("device-1", TOKEN_A);
        store.upsert(record);
        clock.tick();

        assertThat(PushDeviceStore.listStored(varStore)).containsExactly(record);
    }

    @Test
    void listStored_emptyStore_returnsEmptyList() {
        assertThat(PushDeviceStore.listStored(varStore)).isEmpty();
    }

    /// A stored record holds the platform as the string that the phone sent, or holds no platform. [PushDeviceStore#list] reads a known platform name as that
    /// [DevicePlatform], and an unknown name or a missing platform as [DevicePlatform#UNKNOWN].
    @Test
    void list_recordsStoredWithAnyPlatformStringOrNone_readBackWithTheParsedPlatform() {
        Map<String, Object> iosRecord = createStoredRecord("device-1", TOKEN_A);
        iosRecord.put("platform", "ios");
        Map<String, Object> webRecord = createStoredRecord("device-2", TOKEN_B);
        webRecord.put("platform", "web");
        varStore.saveValueEncrypted(PushDeviceStoreImpl.STORE_KEY, Map.of("device-1", iosRecord,
                                                                          "device-2", webRecord,
                                                                          "device-3", createStoredRecord("device-3", TOKEN_C)));

        assertThat(listNow()).containsExactlyInAnyOrder(recordFor("device-1", TOKEN_A).withPlatform(DevicePlatform.IOS),
                                                        recordFor("device-2", TOKEN_B),
                                                        recordFor("device-3", TOKEN_C));
    }

    @ParameterizedTest
    @EnumSource(DevicePlatform.class)
    void upsert_storesThePlatformAsItsNameInLowerCase(DevicePlatform platform) {
        store.upsert(recordFor("device-1", TOKEN_A).withPlatform(platform));
        clock.tick();

        assertThat(varStore.readValueEncrypted(STORED_RECORDS_TYPE, PushDeviceStoreImpl.STORE_KEY))
                .hasValueSatisfying(recordsByDeviceId -> assertThat(recordsByDeviceId).hasEntrySatisfying(
                        "device-1",
                        storedRecord -> assertThat(storedRecord).containsEntry("platform", platform.name().toLowerCase(Locale.ROOT))));
    }

    @Test
    void remove_lastDevice_clearsVarStoreKey() {
        store.upsert(recordFor("device-1", TOKEN_A));
        clock.tick();
        assertThat(varStore.allKeys()).containsExactly(PushDeviceStoreImpl.STORE_KEY);
        // Push tokens are persisted encrypted at rest, not as plaintext JSON.
        assertThat(varStore.rawStoredValue(PushDeviceStoreImpl.STORE_KEY))
                .hasValueSatisfying(stored -> assertThat(EnvelopeEncryption.isEnvelope(stored)).as("push devices stored as encryption envelope").isTrue());

        store.remove("device-1");
        clock.tick();

        assertThat(varStore.allKeys()).isEmpty();
    }

    @Test
    void subscribe_deliversTheRegisteredDevicesThenTheWholeListAfterEachChange() {
        PushDeviceRecord firstRecord = recordFor("device-1", TOKEN_A);
        store.upsert(firstRecord);
        clock.tick();
        var deliveries = new ArrayList<List<PushDeviceRecord>>();

        store.subscribe(deliveries::add);
        PushDeviceRecord secondRecord = recordFor("device-2", TOKEN_B);
        store.upsert(secondRecord);
        store.remove("device-1");
        store.pruneByToken(TOKEN_B);
        clock.tick();

        assertThat(deliveries).containsExactly(List.of(firstRecord), List.of(firstRecord, secondRecord), List.of(secondRecord), List.of());
    }

    @Test
    void subscribe_removalThatRemovesNothing_deliversNothing() {
        store.upsert(recordFor("device-1", TOKEN_A));
        clock.tick();
        var deliveries = new ArrayList<List<PushDeviceRecord>>();
        subscribe(deliveries::add);
        deliveries.clear();

        CompletableFuture<Void> removal = store.remove("nonexistent");
        CompletableFuture<Void> prune = store.pruneByToken("ExponentPushToken[unknown]");
        clock.tick();

        assertThat(removal).succeedsWithin(Duration.ZERO);
        assertThat(prune).succeedsWithin(Duration.ZERO);
        assertThat(deliveries).isEmpty();
    }

    @Test
    void subscribe_closedSubscription_receivesNoFurtherDelivery() {
        var deliveries = new ArrayList<List<PushDeviceRecord>>();
        Closeable subscription = subscribe(deliveries::add);
        deliveries.clear();

        subscription.close();
        clock.tick();
        CompletableFuture<Void> upsert = store.upsert(recordFor("device-1", TOKEN_A));
        clock.tick();

        assertThat(upsert).succeedsWithin(Duration.ZERO);
        assertThat(deliveries).isEmpty();
    }

    /// A subscriber that throws on every delivery stays subscribed until its handle is closed, and the store logs each failure.
    @Test
    void subscribe_subscriberThrowsOnTheRegisteredDevices_logsTheFailureAndStaysSubscribedUntilClosed() {
        var deliveries = new ArrayList<List<PushDeviceRecord>>();
        var failure = new RuntimeException("boom");
        PushDeviceRecord record = recordFor("device-1", TOKEN_A);
        Closeable subscription;
        try (LogCapture log = LogCapture.capture(PushDeviceStoreImpl.class, Level.INFO)) {
            subscription = subscribe(devices -> {
                deliveries.add(devices);
                throw failure;
            });
            CompletableFuture<Void> upsert = store.upsert(record);
            clock.tick();

            assertThat(upsert).succeedsWithin(Duration.ZERO);
            assertThat(log.lines()).hasSize(2).allSatisfy(line -> assertThat(line.thrown()).containsExactly(failure.toString()));
        }

        subscription.close();
        clock.tick();
        CompletableFuture<Void> upsert = store.upsert(recordFor("device-2", TOKEN_B));
        clock.tick();

        assertThat(upsert).succeedsWithin(Duration.ZERO);
        assertThat(deliveries).containsExactly(List.of(), List.of(record));
    }

    /// When the store cannot read the registered devices for a new subscription, the subscription's task fails on the store's executor, and the listener
    /// receives no delivery, including after a later upsert.
    @Test
    void subscribe_registeredDevicesUnreadable_failsTheTaskAndSubscribesNothing() {
        // The store reads an encrypted value under `STORE_KEY`, so this plaintext value makes the read fail.
        varStore.saveValue(PushDeviceStoreImpl.STORE_KEY, Map.of());
        var deliveries = new ArrayList<List<PushDeviceRecord>>();

        store.subscribe(deliveries::add);

        assertThatThrownBy(clock::tick).isInstanceOf(IllegalStateException.class);
        varStore.clearValue(PushDeviceStoreImpl.STORE_KEY);
        CompletableFuture<Void> upsert = store.upsert(recordFor("device-1", TOKEN_A));
        clock.tick();
        assertThat(upsert).succeedsWithin(Duration.ZERO);
        assertThat(deliveries).isEmpty();
    }

    @ParameterizedTest
    @MethodSource
    void upsert_subscriberThrows_completesAndNotifiesTheOtherSubscribers(RuntimeException failure) {
        subscribe(devices -> {
            if (!devices.isEmpty()) {
                throw failure;
            }
        });
        var deliveries = new ArrayList<List<PushDeviceRecord>>();
        subscribe(deliveries::add);
        PushDeviceRecord record = recordFor("device-1", TOKEN_A);

        CompletableFuture<Void> upsert = store.upsert(record);
        clock.tick();

        assertThat(upsert).succeedsWithin(Duration.ZERO);
        assertThat(deliveries).containsExactly(List.of(), List.of(record));
        assertThat(listNow()).containsExactly(record);
    }

    static Stream<Arguments> upsert_subscriberThrows_completesAndNotifiesTheOtherSubscribers() {
        return Stream.of(arguments(new RuntimeException("boom")),
                         // A subscriber's executor throws this exception when its queue is full.
                         arguments(new RejectedExecutionException(REJECTION_MESSAGE)));
    }

    /// When the executor of every subscriber rejects a delivery, the upsert succeeds and the store logs each rejection.
    @Test
    void upsert_everySubscriberRejectsTheDelivery_succeedsAndLogsEachRejection() {
        subscribe(PushDeviceStoreImplTest::rejectAfterTheImage);
        subscribe(PushDeviceStoreImplTest::rejectAfterTheImage);

        try (LogCapture log = LogCapture.capture(PushDeviceStoreImpl.class, Level.INFO)) {
            CompletableFuture<Void> upsert = store.upsert(recordFor("device-1", TOKEN_A));
            clock.tick();

            assertThat(upsert).succeedsWithin(Duration.ZERO);
            var loggedRejection = new LogCapture.CapturedLine(Level.INFO,
                                                              "A subscriber failed",
                                                              List.of(new RejectedExecutionException(REJECTION_MESSAGE).toString()));
            assertThat(log.lines()).containsExactly(loggedRejection, loggedRejection);
        }
    }

    private static void rejectAfterTheImage(List<PushDeviceRecord> devices) {
        if (!devices.isEmpty()) {
            throw new RejectedExecutionException(REJECTION_MESSAGE);
        }
    }

    /// Subscribes `listener`, and advances the clock so that the store delivers the registered devices to `listener`.
    ///
    /// @return the handle that unsubscribes `listener`
    private Closeable subscribe(Consumer<List<PushDeviceRecord>> listener) {
        Closeable subscription = store.subscribe(listener);
        clock.tick();
        return subscription;
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

    /// @return a modifiable map of the properties of a stored record with no platform
    private static Map<String, Object> createStoredRecord(String deviceId, String token) {
        var storedRecord = new HashMap<String, Object>();
        storedRecord.put("deviceId", deviceId);
        storedRecord.put("token", token);
        storedRecord.put("registeredAt", START);
        return storedRecord;
    }
}
