package net.yudichev.jiotty.energy;

import com.google.common.collect.ImmutableList;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPrice;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StrictMath.toIntExact;
import static java.util.Comparator.comparing;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

final class AgilePredictEnergyPriceServiceImpl extends BaseLifecycleComponent implements EnergyPriceService {
    private static final Logger logger = LogManager.getLogger(AgilePredictEnergyPriceServiceImpl.class);
    private static final long PRICE_PERIOD_LENGTH_MIN = 30;
    private static final int PRICE_PERIOD_LENGTH_SEC = toIntExact(TimeUnit.MINUTES.toSeconds(PRICE_PERIOD_LENGTH_MIN));
    private static final Duration RETRIEVAL_PERIOD = Duration.ofMinutes(15);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private final Provider<SchedulingExecutor> executorProvider;
    private final AgilePredictPriceService priceService;
    private final Listeners<Prices> listeners = new Listeners<>();

    private Instant startOfOldestPricePeriod;
    private volatile List<Double> pricesPerPeriod;
    private SchedulingExecutor executor;
    private Closeable refreshSchedule;
    @Nullable
    private Closeable retrySchedule;
    @Nullable
    private Throwable lastFailure;

    @Inject
    public AgilePredictEnergyPriceServiceImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                              AgilePredictPriceService priceService) {
        this.executorProvider = checkNotNull(executorProvider);
        this.priceService = checkNotNull(priceService);
    }

    @Override
    public Optional<Prices> getPrices() {
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(pricesPerPeriod)
                                                          .map(this::constructPrices));
    }

    @Override
    public Closeable subscribeToPrices(Consumer<Prices> consumer) {
        return whenStartedAndNotLifecycling(() -> listeners.addListener(executor, this::getPrices, consumer));
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        throw new UnsupportedOperationException("subscribeToAuthState");
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        refreshSchedule = executor.scheduleAtFixedRate(Duration.ZERO, RETRIEVAL_PERIOD, () -> {
            // stop retries if they overflow until next refresh period
            if (retrySchedule != null) {
                logger.warn("Retries overran until next refresh period, stopping them", lastFailure);
                retrySchedule.close();
                retrySchedule = null;
                lastFailure = null;
            }
            retrievePrices();
        });
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, retrySchedule, refreshSchedule);
    }

    private void retrievePrices() {
        logger.info("Requesting AgilePredict prices for 13 days");
        // TODO:commerce region must be a parameter, # of days must be a dynamic parameter of this service (will need to change on the fly)
        priceService.getPrices("A", 13)
                    .thenAcceptAsync(prices -> listeners.notify(handleAgilePredictPrices(prices)), executor)
                    .whenCompleteAsync((_, throwable) -> {
                        if (throwable != null) {
                            handleFailure(throwable);
                        }
                    }, executor);
    }

    private void handleFailure(Throwable e) {
        lastFailure = e;
        logger.info("Failed retrieving Agile Predict prices, will retry in {}", RETRY_DELAY, e);
        scheduleRetry();
    }

    private Prices handleAgilePredictPrices(List<AgilePredictPrice> prices) {
        retrySchedule = null;
        checkArgument(!prices.isEmpty(), "AgilePredict returned empty list of prices");

        // observed returned prices not sorted by date, so sort it first
        prices = new ArrayList<>(prices);
        prices.sort(comparing(AgilePredictPrice::dateTime));

        startOfOldestPricePeriod = prices.getFirst().dateTime();
        Instant endOfPrices = prices.getLast().dateTime().plusSeconds(PRICE_PERIOD_LENGTH_SEC);
        logger.info("Received prices from {} till {}", startOfOldestPricePeriod, endOfPrices);
        logger.debug("Prices received: {}", prices);
        var newPricesPerPeriodBuilder = ImmutableList.<Double>builder();
        Instant expectedStartTime = startOfOldestPricePeriod;
        for (int i = 0; i < prices.size(); i++) {
            AgilePredictPrice price = prices.get(i);
            var diffFromExpectedToActualTime = Duration.between(expectedStartTime, price.dateTime());
            if (diffFromExpectedToActualTime.equals(ONE_HOUR)) {
                // Handle DST bug https://github.com/fboundy/agile_predict/issues/11 TODO remove when fixed
                // add two missing prices with linearly interpolated values
                assert i > 0;
                AgilePredictPrice previousPrice = prices.get(i - 1);
                double oneThirdOfTheDiff = (price.predictedPrice() - previousPrice.predictedPrice()) / 3.0;
                newPricesPerPeriodBuilder.add(previousPrice.predictedPrice() + oneThirdOfTheDiff);
                newPricesPerPeriodBuilder.add(previousPrice.predictedPrice() + oneThirdOfTheDiff * 2.0);
                expectedStartTime = expectedStartTime.plusSeconds(PRICE_PERIOD_LENGTH_SEC * 2L);
            } else {
                checkArgument(diffFromExpectedToActualTime.isZero(),
                              "Element %s in received prices must have start time %s but was %s: %s",
                              i, expectedStartTime, price.dateTime(), prices);
            }
            newPricesPerPeriodBuilder.add(price.predictedPrice());
            expectedStartTime = expectedStartTime.plusSeconds(PRICE_PERIOD_LENGTH_SEC);
        }
        pricesPerPeriod = newPricesPerPeriodBuilder.build();
        return constructPrices(pricesPerPeriod);
    }

    private Prices constructPrices(List<Double> pricesPerPeriod) {
        return new Prices(startOfOldestPricePeriod, new PriceProfile(PRICE_PERIOD_LENGTH_SEC, 0, pricesPerPeriod));
    }

    private void scheduleRetry() {
        retrySchedule = executor.schedule(RETRY_DELAY, this::retrievePrices);
    }
}
