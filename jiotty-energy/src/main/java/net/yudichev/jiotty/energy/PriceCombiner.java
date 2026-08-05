package net.yudichev.jiotty.energy;

import com.google.common.base.Verify;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.AbstractList;
import java.util.List;

/// Combines a real (already-published, e.g. Agile) price profile with a predicted profile that extends past the real one's end. The combined profile prefers
/// real values where they exist and appends predicted values for the period beyond.
///
/// Both profiles must use the same [PriceProfile#intervalLengthSec()] (verified). The two profiles may start at different instants; if the predicted profile
/// starts after the real profile's end (a gap), the real profile is returned unchanged.
public final class PriceCombiner {
    private static final Logger logger = LogManager.getLogger(PriceCombiner.class);

    private PriceCombiner() {
    }

    public static Prices combine(Prices realPrices, Prices predictedPrices) {
        var intervalLengthSec = realPrices.profile().intervalLengthSec();
        Verify.verify(intervalLengthSec == predictedPrices.profile().intervalLengthSec(),
                      "cannot combine incompatible prices: different interval lengths: %s and %s",
                      intervalLengthSec, predictedPrices.profile().intervalLengthSec());

        int predictedFirstIdx = Math.toIntExact(Duration.between(predictedPrices.profileStart(), realPrices.profileEnd()).getSeconds() / intervalLengthSec);
        if (predictedFirstIdx < 0) {
            // Gap: the predicted window starts after the real window ends, so the two are disjoint and cannot be stitched. Drop the predicted window and return
            // the real prices alone; the next real-price refresh extends the real window and closes the gap.
            logger.warn("Gap between real prices end {} and predicted start {}; dropping predicted window until real prices extend to cover it",
                        realPrices.profileEnd(), predictedPrices.profileStart());
            // TODO:commerce convert this WARN into the long-planned "prices suspicious" flag on the price result, which car-server would then surface as an
            //  Admin Alert instead of a silent degrade.
            return realPrices;
        }
        List<Double> predictedProfile = predictedPrices.profile().pricePerInterval();
        if (predictedFirstIdx >= predictedProfile.size()) {
            return realPrices;
        }
        List<Double> realProfile = realPrices.profile().pricePerInterval();
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
}
