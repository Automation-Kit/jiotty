package net.yudichev.jiotty.energy.octopus.provider;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.energy.EnergyPriceService;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceCombiner;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;

/// A utility for joining the prices of an account's tariff agreements into one [Prices] that runs across the switches from one tariff to the next.
final class AgreementPrices {
    /// The message of the [Failure.PriceRetrievalError] reported when no agreement is in force now. It is constant text, so it holds none of the account's
    /// agreements or supply identifiers.
    public static final String NO_AGREEMENT_IN_FORCE = "No import meter point of the Octopus account has an agreement in force";
    private static final Optional<Instant> NO_REFRESH = Optional.of(EnergyPriceService.NO_REFRESH_EXPECTED);
    private static final JoinedPrices NOTHING_YET = new JoinedPrices(Optional.empty(), Optional.empty());

    private AgreementPrices() {
    }

    /// An agreement, cut off at `windowEnd` of [#join], and what is known of its tariff's prices.
    ///
    /// @param from       the start of the agreement, inclusive
    /// @param to         the end of the agreement, exclusive, or `windowEnd` of [#join] where the agreement lasts past it
    /// @param tariffCode the agreement's tariff
    /// @param prices     what is known of the prices of the agreement's tariff
    public record AgreementInWindow(Instant from, Instant to, String tariffCode, TariffPriceState prices) {
        public AgreementInWindow {
            checkNotNull(from);
            checkNotNull(to);
            checkNotNull(tariffCode);
            checkNotNull(prices);
        }
    }

    /// What is known of the prices of one agreement's tariff.
    public sealed interface TariffPriceState {
        /// The state of a tariff that has not been classified yet.
        final class Resolving implements TariffPriceState {
            public static final Resolving INSTANCE = new Resolving();

            private Resolving() {
            }
        }

        /// The state of a tariff whose classification failed; the classification is tried again after [OctopusPriceSlots#FAILURE_RETRY_DELAY].
        record ResolutionFailed(Throwable cause) implements TariffPriceState {
            public ResolutionFailed {
                checkNotNull(cause);
            }
        }

        /// The state of a tariff that cannot be priced.
        final class Unsupported implements TariffPriceState {
            public static final Unsupported INSTANCE = new Unsupported();

            private Unsupported() {
            }
        }

        /// The state of a tariff that has a price service.
        ///
        /// @param result          the service's latest result; empty until it delivers one
        /// @param nextRefreshTime the service's latest next refresh time; empty until it delivers one
        /// @param dynamic         whether the tariff is Agile, whose prices are joined with the region's forecast
        record Priced(Optional<PriceResult> result, Optional<Instant> nextRefreshTime, boolean dynamic) implements TariffPriceState {
            public Priced {
                checkNotNull(result);
                checkNotNull(nextRefreshTime);
            }
        }
    }

    /// The prices of the agreements joined by [#join], and when they next reach further.
    ///
    /// @param result          empty while the agreement in force now has no result yet
    /// @param nextRefreshTime when the joined prices are next expected to reach further; empty when that is not known
    public record JoinedPrices(Optional<PriceResult> result, Optional<Instant> nextRefreshTime) {
        public JoinedPrices {
            checkNotNull(result);
            checkNotNull(nextRefreshTime);
        }
    }

    /// Joins the prices of `agreements`, starting with the agreement in force at `now`.
    ///
    /// @param agreements must be in time order and must overlap `[now, windowEnd)`
    /// @param forecast   the region's forecast, joined after the actual prices of an Agile agreement; empty when there is none
    /// @return depending on the agreement in force at `now`:
    ///
    /// - none: a [Failure.PriceRetrievalError] whose message is [#NO_AGREEMENT_IN_FORCE];
    /// - one whose tariff is not classified yet, or whose prices have not arrived: an empty result;
    /// - one whose tariff could not be classified, or whose prices could not be fetched: that failure;
    /// - one whose tariff cannot be priced: [Failure.IncompatibleTariff];
    /// - one whose tariff has prices: those prices, followed by the prices of each later agreement over that agreement's period. The prices stop at the
    ///   first agreement that has no prices, whose prices end before the agreement does, or that leaves a gap after the agreement before it.
    public static JoinedPrices join(List<AgreementInWindow> agreements, Instant now, Instant windowEnd, Optional<Prices> forecast) {
        int currentIndex = indexOfAgreementCovering(agreements, now);
        if (currentIndex < 0) {
            return new JoinedPrices(Optional.of(new Failure.PriceRetrievalError(new RuntimeException(NO_AGREEMENT_IN_FORCE))), Optional.empty());
        }
        AgreementInWindow currentAgreement = agreements.get(currentIndex);
        return switch (currentAgreement.prices()) {
            case TariffPriceState.Resolving _ -> NOTHING_YET;
            case TariffPriceState.ResolutionFailed(Throwable cause) -> new JoinedPrices(Optional.of(new Failure.PriceRetrievalError(cause)), Optional.empty());
            case TariffPriceState.Unsupported _ ->
                    new JoinedPrices(Optional.of(new Failure.IncompatibleTariff(currentAgreement.tariffCode())), Optional.empty());
            case TariffPriceState.Priced(var result, var nextRefreshTime, _) -> switch (result.orElse(null)) {
                case null -> new JoinedPrices(Optional.empty(), nextRefreshTime);
                case Failure failure -> new JoinedPrices(Optional.of(failure), nextRefreshTime);
                case Prices _ -> joinFrom(agreements, currentIndex, now, windowEnd, forecast.orElse(null));
            };
        };
    }

    /// @param forecast the region's forecast, `null` when there is none
    private static JoinedPrices joinFrom(List<AgreementInWindow> agreements, int currentIndex, Instant now, Instant windowEnd, @Nullable Prices forecast) {
        List<Prices> parts = new ArrayList<>(agreements.size() - currentIndex);
        Instant previousPartEnd = null;
        Optional<Instant> nextRefreshTime = Optional.empty();
        for (int index = currentIndex; index < agreements.size(); index++) {
            AgreementInWindow agreement = agreements.get(index);
            if (previousPartEnd != null && !agreement.from().equals(previousPartEnd)) {
                // No agreement covers the time between the end of the previous agreement and the start of `agreement`.
                nextRefreshTime = NO_REFRESH;
                break;
            }
            if (!(agreement.prices() instanceof TariffPriceState.Priced(var result, var agreementRefreshTime, boolean dynamic))
                || !(result.orElse(null) instanceof Prices prices)) {
                nextRefreshTime = nextRefreshTimeOfMissing(agreement.prices(), now);
                break;
            }
            Prices part = (dynamic && forecast != null ? PriceCombiner.combine(prices, forecast) : prices).viewCovering(agreement.from(), agreement.to());
            nextRefreshTime = agreementRefreshTime;
            if (previousPartEnd != null && !part.profileStart().equals(agreement.from())) {
                // The tariff's profile starts after the agreement does, which leaves a gap.
                break;
            }
            parts.add(part);
            previousPartEnd = part.profileEnd();
            if (part.profileEnd().isBefore(agreement.to())) {
                break;
            }
            if (index == agreements.size() - 1 && agreement.to().isBefore(windowEnd)) {
                // The last agreement ends inside the window and none follows it.
                nextRefreshTime = NO_REFRESH;
            }
        }
        return new JoinedPrices(Optional.of(concatenate(parts)), nextRefreshTime);
    }

    private static Optional<Instant> nextRefreshTimeOfMissing(TariffPriceState prices, Instant now) {
        return switch (prices) {
            case TariffPriceState.Resolving _, TariffPriceState.ResolutionFailed _ -> Optional.of(now.plus(FAILURE_RETRY_DELAY));
            case TariffPriceState.Unsupported _ -> NO_REFRESH;
            case TariffPriceState.Priced(_, var nextRefreshTime, _) -> nextRefreshTime;
        };
    }

    private static int indexOfAgreementCovering(List<AgreementInWindow> agreements, Instant now) {
        for (int index = 0; index < agreements.size(); index++) {
            AgreementInWindow agreement = agreements.get(index);
            if (!now.isBefore(agreement.from()) && now.isBefore(agreement.to())) {
                return index;
            }
        }
        return -1;
    }

    /// @param parts must join without a gap
    /// @return one profile of `parts`, whose first predicted interval is the first predicted interval of any part
    private static Prices concatenate(List<Prices> parts) {
        Prices firstPart = parts.getFirst();
        if (parts.size() == 1) {
            return firstPart;
        }
        int intervalLengthSec = firstPart.profile().intervalLengthSec();
        int totalSize = 0;
        for (Prices part : parts) {
            totalSize += part.profile().pricePerInterval().size();
        }
        ImmutableList.Builder<Double> pricePerInterval = ImmutableList.builderWithExpectedSize(totalSize);
        int size = 0;
        int idxOfPredictedPriceStart = -1;
        for (Prices part : parts) {
            PriceProfile profile = part.profile();
            verify(profile.intervalLengthSec() == intervalLengthSec,
                   "cannot join prices with different interval lengths: %s and %s",
                   intervalLengthSec,
                   profile.intervalLengthSec());
            if (idxOfPredictedPriceStart < 0 && profile.idxOfPredictedPriceStart() < profile.pricePerInterval().size()) {
                idxOfPredictedPriceStart = size + Math.max(profile.idxOfPredictedPriceStart(), 0);
            }
            pricePerInterval.addAll(profile.pricePerInterval());
            size += profile.pricePerInterval().size();
        }
        return new Prices(firstPart.profileStart(),
                          new PriceProfile(intervalLengthSec, idxOfPredictedPriceStart < 0 ? totalSize : idxOfPredictedPriceStart, pricePerInterval.build()));
    }
}
