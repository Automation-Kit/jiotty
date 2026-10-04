package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolution;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolutionListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.user.persistence.UserPersistenceTestTimeouts.DELIVERY_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;

/// Records what an [IdentityResolutionListener] receives. The first resolution is delivered by a task of its own, so it is awaited; each later one is
/// delivered before the write that caused it completes.
class RecordingIdentityListener implements IdentityResolutionListener {
    private final CompletableFuture<IdentityResolution> firstResolution = new CompletableFuture<>();
    /// Synchronised: the persistence thread records into it, and the test thread reads it.
    private final List<IdentityResolution> resolutions = Collections.synchronizedList(new ArrayList<>());
    /// Synchronised: the persistence thread records into it, and the test thread reads it.
    private final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onResolution(IdentityResolution resolution) {
        resolutions.add(resolution);
        firstResolution.complete(resolution);
    }

    @Override
    public void onSubscriptionFailed(Throwable failure) {
        failures.add(failure);
        firstResolution.completeExceptionally(failure);
    }

    /// Completes with the first resolution, or fails with the subscription's failure.
    public CompletableFuture<IdentityResolution> firstResolution() {
        return firstResolution;
    }

    public void awaitFirst() {
        assertThat(firstResolution).succeedsWithin(DELIVERY_TIMEOUT);
    }

    public List<IdentityResolution> resolutions() {
        synchronized (resolutions) {
            return List.copyOf(resolutions);
        }
    }

    public List<Throwable> failures() {
        synchronized (failures) {
            return List.copyOf(failures);
        }
    }
}
