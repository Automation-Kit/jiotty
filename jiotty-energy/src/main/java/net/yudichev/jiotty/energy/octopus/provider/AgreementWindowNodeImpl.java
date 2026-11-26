package net.yudichev.jiotty.energy.octopus.provider;

import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeterPoint;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Loaded;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.energy.octopus.Bindings.ForwardWindow;

/// An [AgreementWindowNode] that wakes at the next instant at which its value changes: the end of an agreement in the forward window, or the instant at
/// which the forward window first overlaps the first agreement that starts after it.
final class AgreementWindowNodeImpl extends BaseServerNode implements AgreementWindowNode {
    private static final Comparator<Tariff> BY_VALID_FROM = Comparator.comparing(Tariff::validFrom);

    private final Duration forwardWindow;
    private final AccountNode accountNode;

    /// The fetched account from which the agreements of the first import meter point were last read; `null` until an account has been fetched.
    private @Nullable Loaded loadedState;
    /// The agreements of the account's first import meter point, in the order of their start.
    private ImmutableList<Tariff> importAgreements = ImmutableList.of();
    private @Nullable ImmutableList<Tariff> agreements;
    /// The wake-up at the next change of the agreements in the forward window; `null` while none is armed.
    private @Nullable Closeable wakeUp;

    @Inject
    public AgreementWindowNodeImpl(@ForwardWindow Duration forwardWindow, @Assisted GraphRunner runner, @Assisted AccountNode accountNode) {
        super(runner);
        this.forwardWindow = checkNotNull(forwardWindow);
        this.accountNode = subscribeTo(checkNotNull(accountNode));
    }

    @Override
    public @Nullable List<Tariff> agreements() {
        return agreements;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: loadedState={}, importAgreements={}, agreements={}, wakeUpArmed={}", when, loadedState, importAgreements, agreements, wakeUp != null);
    }

    @Override
    protected boolean doWave() {
        if (!(accountNode.state() instanceof Loaded loaded)) {
            return false;
        }
        closeIfNotNull(wakeUp);
        wakeUp = null;
        if (loaded != loadedState) {
            loadedState = loaded;
            importAgreements = ImmutableList.sortedCopyOf(BY_VALID_FROM, importAgreements(loaded.account()));
        }
        Instant now = waveTime();
        Instant windowEnd = now.plus(forwardWindow);
        ImmutableList.Builder<Tariff> overlappingAgreements = ImmutableList.builderWithExpectedSize(importAgreements.size());
        Instant nextChange = null;
        for (Tariff agreement : importAgreements) {
            if (agreement.overlaps(now, windowEnd)) {
                overlappingAgreements.add(agreement);
                nextChange = earlier(nextChange, agreement.validTo().orElse(null));
            } else if (!agreement.validFrom().isBefore(windowEnd)) {
                // The window [t, t + forwardWindow) overlaps the agreement from the first instant t after its start minus forwardWindow.
                nextChange = earlier(nextChange, agreement.validFrom().minus(forwardWindow).plusNanos(1));
            }
        }
        ImmutableList<Tariff> newAgreements = overlappingAgreements.build();
        boolean changed = !newAgreements.equals(agreements);
        if (changed) {
            logger.info("Agreements in the price window now {}", newAgreements);
        }
        agreements = newAgreements;
        if (nextChange != null) {
            wakeUp = scheduleAndCancelOnClose(Duration.between(now, nextChange), () -> {
                wakeUp = null;
                triggerMeAndParentsInNewWave("an Octopus agreement enters or leaves the price window");
            });
        }
        return changed;
    }

    /// @return the agreements of the first property's first import meter point, or none when the account has no such point. An export point is skipped,
    ///         because it carries the export tariff, not the tariff that the user pays for consumption.
    private static List<Tariff> importAgreements(OctopusAccountData account) {
        if (account.properties().isEmpty()) {
            return ImmutableList.of();
        }
        for (ElectricityMeterPoint meterPoint : account.properties().getFirst().electricityMeterPoints()) {
            if (!meterPoint.isExport()) {
                return meterPoint.tariffs();
            }
        }
        return ImmutableList.of();
    }

    /// @param earliest  the earliest change found so far, `null` when none has been
    /// @param candidate `null` for the end of an agreement with no end date
    /// @return the earlier of `earliest` and `candidate`; `null` when both are `null`
    private static @Nullable Instant earlier(@Nullable Instant earliest, @Nullable Instant candidate) {
        if (candidate == null) {
            return earliest;
        }
        return earliest == null || candidate.isBefore(earliest) ? candidate : earliest;
    }

    interface Factory {
        AgreementWindowNodeImpl create(GraphRunner runner, AccountNode accountNode);
    }
}
