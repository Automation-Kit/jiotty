package net.yudichev.jiotty.energy;

import com.google.common.base.Verify;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.AbstractList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.energy.Bindings.AgilePredict;
import static net.yudichev.jiotty.energy.Bindings.Octopus;

final class RealAndPredictedPriceService implements EnergyPriceService {
    private static final Logger logger = LogManager.getLogger(RealAndPredictedPriceService.class);

    private final EnergyPriceService realPricesService;
    private final EnergyPriceService predictedPricesService;

    @Inject
    public RealAndPredictedPriceService(@Octopus EnergyPriceService realPricesService, @AgilePredict EnergyPriceService predictedPricesService) {
        this.realPricesService = checkNotNull(realPricesService);
        this.predictedPricesService = checkNotNull(predictedPricesService);
    }

    @Override
    public Optional<Prices> getPrices() {
        return realPricesService.getPrices().map(real -> predictedPricesService.getPrices()
                                                                               .map(predicted -> combine(real, predicted))
                                                                               .orElse(real));
    }

    @Override
    public Closeable subscribeToPrices(Consumer<Prices> consumer) {
        return new CombiningSubscription(consumer);
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return realPricesService.subscribeToAuthState(consumer);
    }

    private static Prices combine(Prices realPrices, Prices predictedPrices) {
        var intervalLengthSec = realPrices.profile().intervalLengthSec();
        Verify.verify(intervalLengthSec == predictedPrices.profile().intervalLengthSec(),
                      "cannot combine incompatible prices: different interval lengths: %s and %s",
                      intervalLengthSec, predictedPrices.profile().intervalLengthSec());

        int predictedFirstIdx = Math.toIntExact(Duration.between(predictedPrices.profileStart(), realPrices.profileEnd()).getSeconds() / intervalLengthSec);
        Verify.verify(predictedFirstIdx >= 0, "cannot combine prices: gap between real end %s and predicted start %s",
                      realPrices.profileEnd(), predictedPrices.profileStart());
        if (predictedFirstIdx >= predictedPrices.profile().pricePerInterval().size()) {
            return realPrices;
        }
        List<Double> realProfile = realPrices.profile().pricePerInterval();
        List<Double> predictedProfile = predictedPrices.profile().pricePerInterval();
        int size = realProfile.size() + predictedProfile.size() - predictedFirstIdx;
        return new Prices(realPrices.profileStart(),
                          new PriceProfile(intervalLengthSec,
                                           realProfile.size(),
                                           new AbstractList<>() {
                                               @Override
                                               public Double get(int index) {
                                                   return index < realProfile.size() ? realProfile.get(index)
                                                                                     : predictedProfile.get(index - realProfile.size() + predictedFirstIdx);
                                               }

                                               @Override
                                               public int size() {
                                                   return size;
                                               }
                                           }));
    }

    private class CombiningSubscription extends BaseIdempotentCloseable {

        private final Consumer<Prices> consumer;
        private final Closeable subscription;

        private Prices realPrices;
        private Prices predictedPrices;

        public CombiningSubscription(Consumer<Prices> consumer) {
            this.consumer = checkNotNull(consumer);
            subscription = Closeable.forCloseables(realPricesService.subscribeToPrices(this::onRealPrices),
                                                   predictedPricesService.subscribeToPrices(this::onPredictedPrices));
        }

        public void onRealPrices(Prices realPrices) {
            this.realPrices = checkNotNull(realPrices);
            combineAndSend();
        }

        public void onPredictedPrices(Prices predictedPrices) {
            this.predictedPrices = checkNotNull(predictedPrices);
            combineAndSend();
        }

        @Override
        protected void doClose() {
            Closeable.closeSafelyIfNotNull(logger, subscription);
        }

        private void combineAndSend() {
            if (realPrices != null) {
                Prices result;
                if (predictedPrices != null) {
                    result = combine(realPrices, predictedPrices);
                } else {
                    result = realPrices;
                }
                consumer.accept(result);
            }
        }
    }
}
