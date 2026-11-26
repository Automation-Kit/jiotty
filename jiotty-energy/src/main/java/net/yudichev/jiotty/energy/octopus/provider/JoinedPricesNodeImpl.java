package net.yudichev.jiotty.energy.octopus.provider;

import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Loaded;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Unavailable;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.AgreementInWindow;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.JoinedPrices;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.TariffPriceState;
import net.yudichev.jiotty.energy.octopus.provider.ForecastNode.Forecast;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.energy.octopus.Bindings.ForwardWindow;

/// A [JoinedPricesNode] that joins the prices with [AgreementPrices#join] at the wave time, and wakes at the next start or end of an agreement inside the
/// forward window, where the agreement in force, and so the join, changes.
final class JoinedPricesNodeImpl extends BaseServerNode implements JoinedPricesNode {
    private final Duration forwardWindow;
    private final AccountNode accountNode;
    private final AgreementWindowNode agreementWindowNode;
    private final TariffPricesNode tariffPricesNode;
    private final ForecastNode forecastNode;
    private final TariffTimelineNode tariffTimelineNode;

    private @Nullable PriceResult result;
    private @Nullable Instant nextRefreshTime;
    /// The wake-up at the next start or end of an agreement inside the forward window; `null` while none is armed.
    private @Nullable Closeable wakeUp;

    @Inject
    public JoinedPricesNodeImpl(@ForwardWindow Duration forwardWindow,
                                @Assisted GraphRunner runner,
                                @Assisted AccountNode accountNode,
                                @Assisted AgreementWindowNode agreementWindowNode,
                                @Assisted TariffPricesNode tariffPricesNode,
                                @Assisted ForecastNode forecastNode,
                                @Assisted TariffTimelineNode tariffTimelineNode) {
        super(runner);
        this.forwardWindow = checkNotNull(forwardWindow);
        this.accountNode = subscribeTo(checkNotNull(accountNode));
        this.agreementWindowNode = subscribeTo(checkNotNull(agreementWindowNode));
        this.tariffPricesNode = subscribeTo(checkNotNull(tariffPricesNode));
        this.forecastNode = subscribeTo(checkNotNull(forecastNode));
        this.tariffTimelineNode = subscribeTo(checkNotNull(tariffTimelineNode));
    }

    @Override
    public @Nullable PriceResult result() {
        return result;
    }

    @Override
    public @Nullable Instant nextRefreshTime() {
        return nextRefreshTime;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: result={}, nextRefreshTime={}, wakeUpArmed={}", when, result, nextRefreshTime, wakeUp != null);
    }

    @Override
    protected boolean doWave() {
        PriceResult previousResult = result;
        Instant previousNextRefreshTime = nextRefreshTime;
        closeIfNotNull(wakeUp);
        wakeUp = null;
        switch (accountNode.state()) {
            case null -> {
                result = null;
                nextRefreshTime = null;
            }
            case Unavailable(Throwable cause) -> {
                result = new Failure.PriceRetrievalError(cause);
                nextRefreshTime = null;
            }
            case Loaded _ -> join();
        }
        return !Objects.equals(result, previousResult) || !Objects.equals(nextRefreshTime, previousNextRefreshTime);
    }

    private void join() {
        List<Tariff> agreements = agreementWindowNode.agreements();
        Map<String, TariffPriceState> priceStatesByTariffCode = tariffPricesNode.priceStatesByTariffCode();
        Forecast forecast = forecastNode.forecast();
        if (agreements == null || priceStatesByTariffCode == null || forecast == null) {
            return;
        }
        Instant now = waveTime();
        Instant windowEnd = now.plus(forwardWindow);
        ImmutableList.Builder<AgreementInWindow> agreementPrices = ImmutableList.builderWithExpectedSize(agreements.size());
        Instant nextBoundary = null;
        for (Tariff agreement : agreements) {
            Instant endInWindow = agreement.validTo().filter(end -> end.isBefore(windowEnd)).orElse(windowEnd);
            TariffPriceState priceState = priceStatesByTariffCode.get(agreement.tariffCode());
            assert priceState != null : "every tariff in the window has a price state";
            agreementPrices.add(new AgreementInWindow(agreement.validFrom(), endInWindow, agreement.tariffCode(), priceState));
            nextBoundary = earliestInside(nextBoundary, agreement.validFrom(), now, windowEnd);
            nextBoundary = earliestInside(nextBoundary, agreement.validTo().orElse(null), now, windowEnd);
        }
        Optional<Prices> forecastPrices = forecast instanceof Forecast.Available(Prices prices) ? Optional.of(prices) : Optional.empty();
        JoinedPrices joinedPrices = AgreementPrices.join(agreementPrices.build(), now, windowEnd, forecastPrices);
        TariffTimeline timeline = tariffTimelineNode.timeline();
        // The joined prices are withheld until the tariff timeline is known, so that subscribers receive the timeline first; a failure is published at once.
        result = joinedPrices.result().filter(joined -> joined instanceof Failure || timeline != null).orElse(null);
        nextRefreshTime = joinedPrices.nextRefreshTime().orElse(null);
        if (nextBoundary != null) {
            wakeUp = scheduleAndCancelOnClose(Duration.between(now, nextBoundary), () -> {
                wakeUp = null;
                triggerMeAndParentsInNewWave("an Octopus agreement starts or ends");
            });
        }
    }

    /// @param earliest  the earliest boundary found so far, `null` when none has been
    /// @param candidate a start or end of an agreement, `null` for the end of an agreement with no end date
    /// @return the earlier of `earliest` and `candidate`, counting `candidate` only when it is after `now` and before `windowEnd`; `null` when neither is
    ///         such a boundary
    private static @Nullable Instant earliestInside(@Nullable Instant earliest, @Nullable Instant candidate, Instant now, Instant windowEnd) {
        if (candidate == null || !candidate.isAfter(now) || !candidate.isBefore(windowEnd)) {
            return earliest;
        }
        return earliest == null || candidate.isBefore(earliest) ? candidate : earliest;
    }

    interface Factory {
        JoinedPricesNodeImpl create(GraphRunner runner,
                                    AccountNode accountNode,
                                    AgreementWindowNode agreementWindowNode,
                                    TariffPricesNode tariffPricesNode,
                                    ForecastNode forecastNode,
                                    TariffTimelineNode tariffTimelineNode);
    }
}
