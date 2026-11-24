package net.yudichev.jiotty.energy.octopus;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.RateSchedule.RateSegment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.Bindings.ForwardWindow;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.SLOT_LENGTH;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.SLOT_LENGTH_SEC;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.UK_ZONE;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.floorToSlot;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.nextUkTimeOfDayAfter;

/// The price service of a tariff whose rates Octopus publish ahead: a flat rate per price-cap period (Octopus Flexible), bands by time of day (Octopus Go,
/// the older Intelligent Octopus Go products), or day and night rates (the Intelligent Octopus Go `IOG-SMB-` products). One instance per
/// `(productCode, tariffCode)`, serving every subscriber on that tariff.
///
/// Each retrieval reads the rate rows from Octopus from the current slot to the next daily retrieval plus the period set with
/// [OctopusTariffPriceServiceRegistryModule.Builder#setForwardWindow], so that the prices reach that period ahead of now until the next retrieval. The rows
/// are read straight from Octopus: they are only a handful, and a rate with no end date covers future slots, so they must not be cached slot by slot. The
/// rows are turned into priced stretches of time and laid on the half-hour grid. Octopus publish the bands of a time-of-use tariff only a day or two
/// ahead, so each slot after the last published row takes the price at the same UK clock time on the last fully published day. Every price is actual, the
/// repeated ones included: the bands are the tariff's own schedule, and they change only at a price-cap change.
///
/// Retrieval runs on start and daily at 18:00 UK time; a failure is retried 15 minutes later. A failure is published at once when the latest published
/// result is not [Prices]; otherwise the published prices are left unchanged, and the failure is published at the next daily retrieval if it persists.
public final class OctopusPublishedRatePriceServiceImpl extends BaseOctopusTariffPriceService {
    private static final Logger logger = LogManager.getLogger(OctopusPublishedRatePriceServiceImpl.class);
    /// UK clock time of the daily retrieval. A time-of-use tariff's rows for the next day are published by then.
    @VisibleForTesting
    static final LocalTime DAILY_RETRIEVAL_TIME = LocalTime.of(18, 0);
    /// How far before the current slot the rows are read, so that a full day of bands lies behind the last published row to project from.
    private static final Duration LOOKBACK = Duration.ofDays(1);

    private final CurrentDateTimeProvider timeProvider;
    private final Duration forwardWindow;
    private final OctopusRegionService regionService;
    private final RateSchedule rateSchedule;
    private final String productCode;

    @Inject
    public OctopusPublishedRatePriceServiceImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                                CurrentDateTimeProvider timeProvider,
                                                @ForwardWindow Duration forwardWindow,
                                                JobScheduler jobScheduler,
                                                TaskFailureReporter taskFailureReporter,
                                                @Assisted OctopusRegionService regionService,
                                                @Assisted RateSchedule rateSchedule,
                                                @Assisted("productCode") String productCode,
                                                @Assisted("tariffCode") String tariffCode) {
        super(executorProvider, jobScheduler, taskFailureReporter, DAILY_RETRIEVAL_TIME, tariffCode, "Delivering the Octopus published rates");
        this.timeProvider = checkNotNull(timeProvider);
        checkArgument(forwardWindow.isPositive(), "forwardWindow must be positive: %s", forwardWindow);
        this.forwardWindow = forwardWindow;
        this.regionService = checkNotNull(regionService);
        this.rateSchedule = checkNotNull(rateSchedule);
        this.productCode = checkNotNull(productCode);
    }

    @Override
    protected void retrieve() {
        Instant now = timeProvider.currentInstant();
        Instant start = floorToSlot(now);
        Instant nextDailyRetrieval = nextUkTimeOfDayAfter(now, DAILY_RETRIEVAL_TIME);
        Instant end = nextDailyRetrieval.plus(forwardWindow);
        logger.debug("[{}] Requesting rates from {} to {}", tariffCode(), start, end);
        handleOutcome(rateSchedule.fetchSegments(regionService, productCode, tariffCode(), start.minus(LOOKBACK), end, executor())
                                  .thenApplyAsync(segments -> rasterise(segments, start, end), executor()),
                      "onOctopusRates",
                      prices -> onPrices(prices, nextDailyRetrieval),
                      this::onFailure);
    }

    /// Turns `segments` into one price per half-hour slot from `start` to `end`. Where the segments end before `end`, each later slot takes the price at the
    /// same UK clock time on the day before the day on which the segments end. If that day has no price at a slot's clock time, the prices end before that
    /// slot.
    ///
    /// @param segments must be in time order and must not overlap
    /// @throws IllegalStateException if no segment covers `start`
    @VisibleForTesting
    static Prices rasterise(List<RateSegment> segments, Instant start, Instant end) {
        int slotCount = toIntExact(Duration.between(start, end).toSeconds() / SLOT_LENGTH_SEC);
        ImmutableList.Builder<Double> prices = ImmutableList.builderWithExpectedSize(slotCount);
        int publishedSlotCount = 0;
        int segmentIndex = 0;
        Instant slot = start;
        while (publishedSlotCount < slotCount) {
            while (segmentIndex < segments.size() && !slot.isBefore(segments.get(segmentIndex).to())) {
                segmentIndex++;
            }
            if (segmentIndex == segments.size() || slot.isBefore(segments.get(segmentIndex).from())) {
                break;
            }
            prices.add(segments.get(segmentIndex).price());
            publishedSlotCount++;
            slot = slot.plus(SLOT_LENGTH);
        }
        checkState(publishedSlotCount > 0, "Octopus published no rate covering %s", start);
        LocalDate referenceDay = slot.atZone(UK_ZONE).toLocalDate().minusDays(1);
        for (int index = publishedSlotCount; index < slotCount; index++, slot = slot.plus(SLOT_LENGTH)) {
            RateSegment segment = covering(segments, referenceDay.atTime(slot.atZone(UK_ZONE).toLocalTime()).atZone(UK_ZONE).toInstant());
            if (segment == null) {
                break;
            }
            prices.add(segment.price());
        }
        ImmutableList<Double> pricePerInterval = prices.build();
        return new Prices(start, new PriceProfile(SLOT_LENGTH_SEC, pricePerInterval.size(), pricePerInterval));
    }

    /// Finds the segment by binary search, so `instant` may come in any order from one call to the next.
    ///
    /// @param segments must be in time order and must not overlap
    /// @return the segment that covers `instant`, `null` when none does
    private static @Nullable RateSegment covering(List<RateSegment> segments, Instant instant) {
        int low = 0;
        int high = segments.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            RateSegment segment = segments.get(middle);
            if (instant.isBefore(segment.from())) {
                high = middle - 1;
            } else if (instant.isBefore(segment.to())) {
                return segment;
            } else {
                low = middle + 1;
            }
        }
        return null;
    }

    private void onPrices(Prices prices, Instant nextDailyRetrieval) {
        cancelPendingAttempt();
        publishPrices(prices);
        publishNextRefreshTime(nextDailyRetrieval);
    }

    private void onFailure(Throwable throwable) {
        logger.info("[{}] Failed retrieving Octopus rates, will retry in {}", tariffCode(), FAILURE_RETRY_DELAY, throwable);
        if (latestResult() instanceof Prices) {
            deferFailure(throwable);
        } else {
            publishFailure(throwable);
        }
        scheduleRetry(this::retrieve, timeProvider.currentInstant());
    }
}
