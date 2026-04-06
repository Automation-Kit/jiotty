package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class StatusHistoryDisplayableTest {
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = T1.plus(Duration.ofMinutes(1));
    private static final Instant T3 = T1.plus(Duration.ofMinutes(2));
    private static final Instant T4 = T1.plus(Duration.ofMinutes(3));

    private StatusHistoryDisplayable<String, String> displayable;

    @BeforeEach
    void setUp() {
        displayable = new StatusHistoryDisplayable<>("Test Title", 3, key -> "Device: " + key);
    }

    @Test
    void getIdReturnsTitle() {
        assertThat(displayable.getId()).isEqualTo("Test Title");
    }

    @Test
    void supportsDataReturnsTrue() {
        assertThat(displayable.supportsData()).isTrue();
    }

    @Test
    void visibleReturnsTrue() {
        assertThat(displayable.visible()).isTrue();
    }

    @Test
    void toDtoWithNoEventsReturnsEmptyGroups() {
        HistoryDisplayableDto dto = toDto();
        assertThat(dto.groups()).isEmpty();
    }

    @Test
    void singleEventProducesCorrectDto() {
        displayable.addEvent("k1", "online", T1);

        HistoryDisplayableDto dto = toDto();
        assertThat(dto.groups())
                .hasEntrySatisfying("Device: k1", entries -> assertThat(entries).satisfiesExactly(entry -> {
                    assertThat(entry.time()).isEqualTo(T1);
                    assertThat(entry.format()).isEqualTo(HistoryDisplayableDto.Format.PLAIN_TEXT);
                    assertThat(entry.value()).isEqualTo("online");
                }))
                .hasSize(1);
    }

    @Test
    void multipleEventsSameKeyAppearNewestFirst() {
        displayable.addEvent("k1", "a", T1);
        displayable.addEvent("k1", "b", T2);
        displayable.addEvent("k1", "c", T3);

        HistoryDisplayableDto dto = toDto();
        assertThat(dto.groups())
                .hasEntrySatisfying("Device: k1", entries -> assertThat(entries).satisfiesExactly(
                        entry -> {
                            assertThat(entry.time()).isEqualTo(T3);
                            assertThat(entry.value()).isEqualTo("c");
                        },
                        entry -> {
                            assertThat(entry.time()).isEqualTo(T2);
                            assertThat(entry.value()).isEqualTo("b");
                        },
                        entry -> {
                            assertThat(entry.time()).isEqualTo(T1);
                            assertThat(entry.value()).isEqualTo("a");
                        }
                ))
                .hasSize(1);
    }

    @Test
    void multipleKeysProduceSeparateGroups() {
        displayable.addEvent("k1", "a", T1);
        displayable.addEvent("k2", "b", T2);

        HistoryDisplayableDto dto = toDto();
        assertThat(dto.groups())
                .hasEntrySatisfying("Device: k1", entries -> assertThat(entries).hasSize(1))
                .hasEntrySatisfying("Device: k2", entries -> assertThat(entries).hasSize(1))
                .hasSize(2);
    }

    @Test
    void windowSizeEvictsOldestEvents() {
        displayable.addEvent("k1", "a", T1);
        displayable.addEvent("k1", "b", T2);
        displayable.addEvent("k1", "c", T3);
        displayable.addEvent("k1", "d", T4);

        HistoryDisplayableDto dto = toDto();
        assertThat(dto.groups())
                .hasEntrySatisfying("Device: k1", entries -> assertThat(entries).satisfiesExactly(
                        entry -> assertThat(entry.value()).isEqualTo("d"),
                        entry -> assertThat(entry.value()).isEqualTo("c"),
                        entry -> assertThat(entry.value()).isEqualTo("b")
                ))
                .hasSize(1);
    }

    @Test
    void nullKeyMappedToEmptyString() {
        displayable.addEvent(null, "value", T1);

        HistoryDisplayableDto dto = toDto();
        assertThat(dto.groups()).containsOnlyKeys("");
    }

    @Test
    void subscribeForUpdatesCallsListenerImmediately() {
        var callCount = new MutableReference<>(0);
        displayable.subscribeForUpdates(() -> callCount.set(callCount.get() + 1));
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void subscribeForUpdatesNotifiesOnAddEvent() {
        var callCount = new MutableReference<>(0);
        displayable.subscribeForUpdates(() -> callCount.set(callCount.get() + 1));
        // reset after initial call
        callCount.set(0);

        displayable.addEvent("k1", "a", T1);
        assertThat(callCount.get()).isEqualTo(1);

        displayable.addEvent("k1", "b", T2);
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void unsubscribeStopsNotifications() {
        var callCount = new MutableReference<>(0);
        Closeable subscription = displayable.subscribeForUpdates(() -> callCount.set(callCount.get() + 1));
        callCount.set(0);

        subscription.close();
        displayable.addEvent("k1", "a", T1);
        assertThat(callCount.get()).isEqualTo(0);
    }

    @Test
    void objectFormatStoresRawStatusObject() {
        var objectDisplayable = new StatusHistoryDisplayable<>(
                "title",
                10,
                String::valueOf,
                DeviceStatus::lastChanged,
                (_, _) -> {},
                (_, _) -> CompletableFuture.completedFuture(null),
                HistoryDisplayableDto.Format.OBJECT);

        objectDisplayable.addEvent("k1", "raw-value", T1);

        HistoryDisplayableDto dto = (HistoryDisplayableDto) objectDisplayable.toDto().getNow(null);
        assertThat(dto.groups())
                .hasEntrySatisfying("k1", entries -> assertThat(entries).satisfiesExactly(entry -> {
                    assertThat(entry.format()).isEqualTo(HistoryDisplayableDto.Format.OBJECT);
                    assertThat(entry.value()).isEqualTo("raw-value");
                }))
                .hasSize(1);
    }

    @Test
    void customStatusToEventTimeIsUsed() {
        Instant customTime = Instant.parse("2099-12-31T23:59:59Z");
        var customDisplayable = new StatusHistoryDisplayable<>(
                "title",
                10,
                String::valueOf,
                _ -> customTime,
                (status, appender) -> appender.append(status.status()),
                (_, _) -> CompletableFuture.completedFuture(null),
                HistoryDisplayableDto.Format.PLAIN_TEXT);

        customDisplayable.addEvent("k1", "val", T1);

        HistoryDisplayableDto dto = (HistoryDisplayableDto) customDisplayable.toDto().getNow(null);
        assertThat(dto.groups())
                .hasEntrySatisfying("k1", entries -> assertThat(entries).satisfiesExactly(
                        entry -> assertThat(entry.time()).isEqualTo(customTime)
                ))
                .hasSize(1);
    }

    @Test
    void handleDownloadDelegatesToHandler(@Mock HttpServletResponse response) {
        var handlerCalled = new MutableReference<>(false);
        CompletableFuture<Void> expectedFuture = CompletableFuture.completedFuture(null);
        var customDisplayable = new StatusHistoryDisplayable<String, String>(
                "title",
                10,
                String::valueOf,
                DeviceStatus::lastChanged,
                (status, appender) -> appender.append(status.status()),
                (downloadId, resp) -> {
                    handlerCalled.set(true);
                    assertThat(downloadId).isEqualTo("dl-1");
                    assertThat(resp).isSameAs(response);
                    return expectedFuture;
                },
                HistoryDisplayableDto.Format.PLAIN_TEXT);

        CompletableFuture<Void> result = customDisplayable.handleDownload("dl-1", response);
        assertThat(handlerCalled.get()).isTrue();
        assertThat(result).isSameAs(expectedFuture);
    }

    @Test
    void constructorRejectsNonPositiveWindowSize() {
        assertThatThrownBy(() -> new StatusHistoryDisplayable<>("title", 0, String::valueOf))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toDtoTypeIsHistory() {
        HistoryDisplayableDto dto = toDto();
        assertThat(dto.type()).isEqualTo("history");
    }

    private HistoryDisplayableDto toDto() {
        return (HistoryDisplayableDto) displayable.toDto().getNow(null);
    }
}
