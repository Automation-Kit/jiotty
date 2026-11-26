package net.yudichev.jiotty.energy.octopus.provider;

import com.google.common.collect.ImmutableMap;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistry;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.provider.ResolutionHistory.FailedResolution;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Failed;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolved;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolving;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;

/// A [TariffSourcesNode] that resolves each tariff once while that tariff is in the forward window. A resolution that ends is stored until the next wave.
/// All failed resolutions are retried together, [OctopusPriceSlots#FAILURE_RETRY_DELAY] after the earliest failure that has not been retried. The earliest
/// failure may be one recorded in the [ResolutionHistory], so a graph rebuilt after a panic resolves such a tariff at the earliest
/// [OctopusPriceSlots#FAILURE_RETRY_DELAY] after its recorded failure.
final class TariffSourcesNodeImpl extends BaseServerNode implements TariffSourcesNode {
    private final OctopusTariffPriceServiceRegistry registry;
    private final AgreementWindowNode agreementWindowNode;
    private final ResolutionHistory resolutionHistory;
    /// The resolutions that have ended since the last wave.
    private final List<ResolutionEnd> pendingResolutionEnds = new ArrayList<>();
    /// The state of the tariff of each agreement in the forward window.
    private final Map<String, TariffState> tariffStatesByTariffCode = new HashMap<>();

    private boolean pendingRetry;
    private @Nullable ImmutableMap<String, TariffResolution> resolutionsByTariffCode;
    /// The timer of the next retry of the failed resolutions; `null` while none is armed.
    private @Nullable Closeable retryTimer;

    @Inject
    public TariffSourcesNodeImpl(OctopusTariffPriceServiceRegistry registry,
                                 @Assisted GraphRunner runner,
                                 @Assisted AgreementWindowNode agreementWindowNode,
                                 @Assisted ResolutionHistory resolutionHistory) {
        super(runner);
        this.registry = checkNotNull(registry);
        this.agreementWindowNode = subscribeTo(checkNotNull(agreementWindowNode));
        this.resolutionHistory = checkNotNull(resolutionHistory);
    }

    @Override
    public @Nullable Map<String, TariffResolution> resolutionsByTariffCode() {
        return resolutionsByTariffCode;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: pendingResolutionEnds={}, tariffStatesByTariffCode={}, pendingRetry={}, resolutionsByTariffCode={}, retryTimerArmed={}",
                     when, pendingResolutionEnds, tariffStatesByTariffCode, pendingRetry, resolutionsByTariffCode, retryTimer != null);
    }

    @Override
    protected boolean doWave() {
        List<Tariff> agreements = agreementWindowNode.agreements();
        if (agreements == null) {
            return false;
        }
        Instant now = waveTime();
        applyResolutionEnds(now);
        boolean retryDue = pendingRetry;
        pendingRetry = false;
        // Indexes the tariff codes in the forward window, so that dropping the state of each tariff that has left the forward window costs one lookup.
        Set<String> tariffCodesInWindow = HashSet.newHashSet(agreements.size());
        for (Tariff agreement : agreements) {
            tariffCodesInWindow.add(agreement.tariffCode());
        }
        tariffStatesByTariffCode.keySet().removeIf(tariffCode -> {
            if (tariffCodesInWindow.contains(tariffCode)) {
                return false;
            }
            logger.info("Tariff {} has left the price window", tariffCode);
            return true;
        });
        for (String tariffCode : tariffCodesInWindow) {
            TariffState state = tariffStatesByTariffCode.get(tariffCode);
            if (state == null) {
                FailedResolution failedResolution = resolutionHistory.failedResolution(tariffCode).orElse(null);
                if (failedResolution != null && failedResolution.at().plus(FAILURE_RETRY_DELAY).isAfter(now)) {
                    tariffStatesByTariffCode.put(tariffCode, new TariffState(new Failed(failedResolution.cause()), failedResolution.at()));
                } else {
                    resolve(tariffCode);
                }
            } else if (retryDue && state.resolution instanceof Failed) {
                resolve(tariffCode);
            }
        }
        armRetryTimerIfFailed(now);
        ImmutableMap.Builder<String, TariffResolution> newResolutions = ImmutableMap.builderWithExpectedSize(tariffStatesByTariffCode.size());
        tariffStatesByTariffCode.forEach((tariffCode, state) -> newResolutions.put(tariffCode, state.resolution));
        ImmutableMap<String, TariffResolution> newResolutionsByTariffCode = newResolutions.build();
        boolean changed = !newResolutionsByTariffCode.equals(resolutionsByTariffCode);
        resolutionsByTariffCode = newResolutionsByTariffCode;
        return changed;
    }

    /// Applies each resolution that has ended since the last wave to the state of its tariff, unless the tariff has left the forward window or has been
    /// resolved again since.
    private void applyResolutionEnds(Instant now) {
        for (ResolutionEnd end : pendingResolutionEnds) {
            if (tariffStatesByTariffCode.get(end.tariffCode()) == end.state()) {
                Throwable failure = end.failure();
                if (failure == null) {
                    TariffPriceSource source = end.source();
                    assert source != null : "a resolution that has not failed has a source";
                    end.state().resolution = new Resolved(source);
                    resolutionHistory.recordSuccess(end.tariffCode());
                } else {
                    logger.info("Failed to resolve Octopus tariff {}, will retry in {}", end.tariffCode(), FAILURE_RETRY_DELAY, failure);
                    end.state().resolution = new Failed(failure);
                    end.state().failedAt = now;
                    resolutionHistory.recordFailure(end.tariffCode(), new FailedResolution(now, failure));
                }
            }
        }
        pendingResolutionEnds.clear();
    }

    /// Starts resolving `tariffCode`, in place of any earlier resolution of it, and stores the resolution for the next wave when it ends.
    private void resolve(String tariffCode) {
        TariffState state = new TariffState(Resolving.INSTANCE, null);
        tariffStatesByTariffCode.put(tariffCode, state);
        registry.forTariff(tariffCode).whenComplete((source, failure) -> runner.executor().tryExecute("onTariffResolved", () -> {
            pendingResolutionEnds.add(new ResolutionEnd(tariffCode, state, source, failure));
            triggerInNewWave("tariff resolved");
        }));
    }

    /// Arms the retry timer for [OctopusPriceSlots#FAILURE_RETRY_DELAY] after the earliest failure that has not been retried, unless the timer is armed or
    /// no resolution has failed.
    private void armRetryTimerIfFailed(Instant now) {
        if (retryTimer != null) {
            return;
        }
        Instant earliestFailure = null;
        for (TariffState state : tariffStatesByTariffCode.values()) {
            if (state.failedAt != null && (earliestFailure == null || state.failedAt.isBefore(earliestFailure))) {
                earliestFailure = state.failedAt;
            }
        }
        if (earliestFailure != null) {
            // The timer fires FAILURE_RETRY_DELAY after the earliest failure, and the retry resolves every failed tariff, including the tariffs that failed
            // later and have waited less than that delay.
            retryTimer = scheduleAndCancelOnClose(Duration.between(now, earliestFailure.plus(FAILURE_RETRY_DELAY)), () -> {
                retryTimer = null;
                pendingRetry = true;
                triggerMeAndParentsInNewWave("failed tariff resolutions due for a retry");
            });
        }
    }

    /// The state of the tariff of an agreement in the forward window. A new resolution of the tariff makes a new instance, so the end of an earlier
    /// resolution can be told apart.
    private static final class TariffState {
        private TariffResolution resolution;
        /// When the resolution failed; `null` unless it has failed.
        private @Nullable Instant failedAt;

        /// @param failedAt when the resolution failed; `null` unless `resolution` is [Failed]
        TariffState(TariffResolution resolution, @Nullable Instant failedAt) {
            this.resolution = checkNotNull(resolution);
            this.failedAt = failedAt;
        }

        @Override
        public String toString() {
            return resolution.toString();
        }
    }

    /// @param tariffCode the tariff whose resolution ended
    /// @param state      the state that the resolution was started for
    /// @param source     where the prices of the tariff come from; `null` when the resolution failed
    /// @param failure    why the resolution failed; `null` when it succeeded
    private record ResolutionEnd(String tariffCode, TariffState state, @Nullable TariffPriceSource source, @Nullable Throwable failure) {
    }

    interface Factory {
        /// @param resolutionHistory the failed resolutions from which this node starts, and which it updates as each resolution ends
        TariffSourcesNodeImpl create(GraphRunner runner, AgreementWindowNode agreementWindowNode, ResolutionHistory resolutionHistory);
    }
}
