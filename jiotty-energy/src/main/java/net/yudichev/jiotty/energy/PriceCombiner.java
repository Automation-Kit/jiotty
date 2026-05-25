package net.yudichev.jiotty.energy;

import com.google.common.base.Verify;

import java.time.Duration;
import java.util.AbstractList;
import java.util.List;

/// Combines a real (already-published, e.g. Agile) price profile with a predicted profile that extends past the real one's end. The combined profile prefers
/// real values where they exist and appends predicted values for the period beyond.
///
/// Both profiles must use the same [PriceProfile#intervalLengthSec()] (verified). The two profiles may start at different instants; the predicted profile must
/// start at or before the real profile's end so the two windows meet.
final class PriceCombiner {
    private PriceCombiner() {
    }

    static Prices combine(Prices realPrices, Prices predictedPrices) {
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
}
