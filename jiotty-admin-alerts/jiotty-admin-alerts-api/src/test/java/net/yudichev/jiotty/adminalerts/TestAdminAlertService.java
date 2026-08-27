package net.yudichev.jiotty.adminalerts;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.common.time.TimeProvider;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

/// In-memory test double for [AdminAlertService]. Synchronous; suitable for single-threaded tests. All futures are completed by the time the call returns.
/// Iteration order on the inspection methods is the order alerts were first raised — easy to assert against in tests.
///
/// Mirrors the production bundle+events model: each [#raise(AdminAlertData)] finds-or-creates a bundle keyed by [AdminAlertData#key()] and appends a
/// [TestEvent] carrying its own occurrence time and description. Sliding-window caps on bundles and events match the production defaults (100 each), with a
/// constructor overload to override.
public final class TestAdminAlertService implements AdminAlertService {
    private static final int DEFAULT_MAX_BUNDLES = 100;
    private static final int DEFAULT_MAX_EVENTS_PER_BUNDLE = 100;

    private final Map<String, AdminAlert> alertsById = new LinkedHashMap<>();
    private final Map<String, String> activeIdByKey = new HashMap<>();
    private final Map<String, List<TestEvent>> eventsByAlertId = new HashMap<>();
    private final CurrentDateTimeProvider timeProvider;
    private final int maxBundles;
    private final int maxEventsPerBundle;
    private long nextIdCounter = 1;
    /// Action to run at the start of the next resolve, installed by [#runBeforeNextResolve]; `null` when no interleaving is staged.
    private @Nullable Runnable beforeNextResolve;

    public TestAdminAlertService() {
        this(new TimeProvider(), DEFAULT_MAX_BUNDLES, DEFAULT_MAX_EVENTS_PER_BUNDLE);
    }

    public TestAdminAlertService(CurrentDateTimeProvider timeProvider) {
        this(timeProvider, DEFAULT_MAX_BUNDLES, DEFAULT_MAX_EVENTS_PER_BUNDLE);
    }

    public TestAdminAlertService(CurrentDateTimeProvider timeProvider, int maxBundles, int maxEventsPerBundle) {
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        checkArgument(maxBundles > 0, "maxBundles must be > 0, was %s", maxBundles);
        checkArgument(maxEventsPerBundle > 0, "maxEventsPerBundle must be > 0, was %s", maxEventsPerBundle);
        this.maxBundles = maxBundles;
        this.maxEventsPerBundle = maxEventsPerBundle;
    }

    @Override
    public String raise(AdminAlertData data) {
        checkNotNull(data, "data");
        String key = data.key();
        Instant now = timeProvider.currentInstant();
        String existingId = activeIdByKey.get(key);
        String bundleId;
        if (existingId == null) {
            enforceBundleCap();
            String newId = "test-" + nextIdCounter++;
            alertsById.put(newId, AdminAlert.builder()
                                            .setId(newId)
                                            .setKey(key)
                                            .setTitle(data.title())
                                            .setSeverity(data.severity())
                                            .setLabels(data.labels())
                                            .setFirstSeenAt(now)
                                            .setLastSeenAt(now)
                                            .setEventCount(1)
                                            .build());
            activeIdByKey.put(key, newId);
            eventsByAlertId.put(newId, new ArrayList<>());
            bundleId = newId;
        } else {
            alertsById.computeIfPresent(existingId, (_, existing) -> AdminAlert.builder()
                                                                               .from(existing)
                                                                               .setLastSeenAt(now)
                                                                               .setEventCount(existing.eventCount() + 1)
                                                                               .build());
            bundleId = existingId;
        }
        appendEvent(bundleId, now, data.description());
        return key;
    }

    /// Runs `action` at the start of the next [#resolve(String, String)] call and then forgets it. Lets a single-threaded test drive an interleaving that
    /// would otherwise need two threads: whatever the caller does between deciding to resolve and resolving.
    public void runBeforeNextResolve(Runnable action) {
        beforeNextResolve = checkNotNull(action, "action");
    }

    @Override
    public CompletableFuture<Optional<String>> resolve(String key, String note) {
        checkNotNull(key, "key");
        checkNotNull(note, "note");
        if (beforeNextResolve != null) {
            Runnable action = beforeNextResolve;
            beforeNextResolve = null;
            action.run();
        }
        String id = activeIdByKey.remove(key);
        if (id == null) {
            return completedFuture(Optional.empty());
        }
        alertsById.computeIfPresent(id, (_, existing) -> AdminAlert.builder()
                                                                   .from(existing)
                                                                   .setResolvedAt(timeProvider.currentInstant())
                                                                   .setResolvedBy("system")
                                                                   .setResolutionNote(note)
                                                                   .build());
        return completedFuture(Optional.of(id));
    }

    @Override
    public CompletableFuture<ResolveByIdOutcome> resolveById(String alertId, String resolvedBy, Optional<String> note) {
        checkNotNull(alertId, "alertId");
        checkNotNull(resolvedBy, "resolvedBy");
        checkNotNull(note, "note");
        AdminAlert alert = alertsById.get(alertId);
        if (alert == null) {
            return completedFuture(ResolveByIdOutcome.UNKNOWN);
        }
        if (alert.resolvedAt().isPresent()) {
            return completedFuture(ResolveByIdOutcome.ALREADY_RESOLVED);
        }
        alertsById.put(alertId, AdminAlert.builder()
                                          .from(alert)
                                          .setResolvedAt(timeProvider.currentInstant())
                                          .setResolvedBy(resolvedBy)
                                          .setResolutionNote(note)
                                          .build());
        activeIdByKey.remove(alert.key());
        return completedFuture(ResolveByIdOutcome.RESOLVED);
    }

    @Override
    public CompletableFuture<Integer> deleteResolvedOlderThan(Duration retention) {
        checkNotNull(retention, "retention");
        Instant cutoff = timeProvider.currentInstant().minus(retention);
        int before = alertsById.size();
        alertsById.entrySet().removeIf(entry -> {
            AdminAlert a = entry.getValue();
            if (a.resolvedAt().isPresent() && a.resolvedAt().get().isBefore(cutoff)) {
                eventsByAlertId.remove(entry.getKey());
                return true;
            }
            return false;
        });
        return completedFuture(before - alertsById.size());
    }

    @Override
    public CompletableFuture<Integer> deleteByLabel(String labelName, String labelValue) {
        // Rejects the same arguments the Postgres implementation rejects, so a test cannot pass against a call production would throw on.
        checkNotNull(labelName, "labelName");
        checkNotNull(labelValue, "labelValue");
        checkArgument(!labelName.isBlank(), "labelName must not be blank");
        checkArgument(!labelValue.isBlank(), "labelValue must not be blank");
        int before = alertsById.size();
        alertsById.entrySet().removeIf(entry -> {
            if (labelValue.equals(entry.getValue().labels().get(labelName))) {
                eventsByAlertId.remove(entry.getKey());
                activeIdByKey.values().removeIf(entry.getKey()::equals);
                return true;
            }
            return false;
        });
        return completedFuture(before - alertsById.size());
    }

    private void enforceBundleCap() {
        int toDelete = alertsById.size() - (maxBundles - 1);
        if (toDelete <= 0) {
            return;
        }
        var iterator = alertsById.entrySet().iterator();
        while (toDelete > 0 && iterator.hasNext()) {
            var entry = iterator.next();
            iterator.remove();
            eventsByAlertId.remove(entry.getKey());
            activeIdByKey.values().removeIf(entry.getKey()::equals);
            toDelete--;
        }
    }

    private void appendEvent(String bundleId, Instant occurredAt, String description) {
        List<TestEvent> events = eventsByAlertId.computeIfAbsent(bundleId, _ -> new ArrayList<>());
        int toDelete = events.size() - (maxEventsPerBundle - 1);
        for (int i = 0; i < toDelete; i++) {
            events.removeFirst();
        }
        events.add(new TestEvent(occurredAt, description));
    }

    /// Most recent bundle (active or resolved) for the given key, or empty if none. Mirrors the persistence-backed `findByKey` for tests that hold the
    /// key returned by [#raise(AdminAlertData)].
    public Optional<AdminAlert> findByKey(String key) {
        checkNotNull(key, "key");
        AdminAlert latest = null;
        for (AdminAlert candidate : alertsById.values()) {
            if (candidate.key().equals(key) && (latest == null || candidate.firstSeenAt().isAfter(latest.firstSeenAt()))) {
                latest = candidate;
            }
        }
        return Optional.ofNullable(latest);
    }

    /// All bundles (active + resolved), keyed by id, in raise order.
    public Map<String, AdminAlert> alertsById() {
        return ImmutableMap.copyOf(alertsById);
    }

    /// Active bundles (those without [AdminAlert#resolvedAt()]), keyed by id, in raise order. Prefer this over [#alertsById()] in tests that assert on raise
    /// outcomes — using [#alertsById()] would silently pass once the alert is resolved.
    public Map<String, AdminAlert> activeAlertsById() {
        ImmutableMap.Builder<String, AdminAlert> builder = ImmutableMap.builder();
        alertsById.forEach((id, alert) -> {
            if (alert.resolvedAt().isEmpty()) {
                builder.put(id, alert);
            }
        });
        return builder.build();
    }

    /// Resolved bundles (those with [AdminAlert#resolvedAt()] present), keyed by id, in raise order.
    public Map<String, AdminAlert> resolvedAlertsById() {
        ImmutableMap.Builder<String, AdminAlert> builder = ImmutableMap.builder();
        alertsById.forEach((id, alert) -> {
            if (alert.resolvedAt().isPresent()) {
                builder.put(id, alert);
            }
        });
        return builder.build();
    }

    /// Events appended to the given bundle, oldest first. Empty if the bundle id is unknown.
    public List<TestEvent> eventsByAlertId(String alertId) {
        checkNotNull(alertId, "alertId");
        List<TestEvent> events = eventsByAlertId.get(alertId);
        return events == null ? ImmutableList.of() : ImmutableList.copyOf(events);
    }

    public record TestEvent(Instant occurredAt, String description) {
        public TestEvent {
            checkNotNull(occurredAt, "occurredAt");
            checkNotNull(description, "description");
        }
    }
}
