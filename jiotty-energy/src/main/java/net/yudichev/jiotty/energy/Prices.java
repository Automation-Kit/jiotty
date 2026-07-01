package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

public final class Prices implements StringFormattable {
    private final Instant profileStart;
    private final PriceProfile profile;

    private Instant profileEnd;
    private Duration duration;

    public Prices(Instant profileStart, PriceProfile profile) {
        this.profileStart = profileStart;
        this.profile = profile;
    }

    public PriceProfile profile() {
        return profile;
    }

    public Instant profileStart() {
        return profileStart;
    }

    public Instant profileEnd() {
        if (profileEnd == null) {
            profileEnd = profileStart.plusSeconds((long) profile.intervalLengthSec() * profile.pricePerInterval().size());
        }
        return profileEnd;
    }

    public Duration duration() {
        if (duration == null) {
            duration = Duration.between(profileStart(), profileEnd());
        }
        return duration;
    }

    public Instant startOfProfileIndex(int index) {
        checkArgument(index >= 0 && index <= profile.pricePerInterval().size()); // NB allow idx == size
        return index == 0 ? profileStart :
               index == profile.pricePerInterval().size() ? profileEnd
                                                          : profileStart.plusSeconds(index * (long) profile.intervalLengthSec());
    }

    public Instant endOfProfileIndex(int index) {
        checkArgument(index >= 0 && index < profile.pricePerInterval().size());
        return index == profile.pricePerInterval().size() - 1 ? profileEnd() : startOfProfileIndex(index + 1);
    }

    public int profileIndexOf(Instant t) {
        long offset = Duration.between(profileStart(), t).toSeconds();
        if (offset < 0) {
            return -1;
        }
        int idx = (int) (offset / profile().intervalLengthSec());
        int max = profile().pricePerInterval().size() - 1;
        if (idx > max) {
            return -1;
        }
        return idx;
    }

    public Prices limitTo(Duration maxLength) {
        if (maxLength.compareTo(duration()) >= 0) {
            return this;
        }
        checkArgument(!maxLength.isNegative(), "maxLength must be >=0 but was %s", maxLength);

        List<Double> thisPricePerInterval = profile.pricePerInterval();
        int newSize = Math.toIntExact(maxLength.toSeconds() / profile.intervalLengthSec());
        return new Prices(profileStart,
                          new PriceProfile(profile.intervalLengthSec(),
                                           profile.idxOfPredictedPriceStart(),
                                           new AbstractList<>() {
                                               @Override
                                               public Double get(int index) {
                                                   return thisPricePerInterval.get(index);
                                               }

                                               @Override
                                               public int size() {
                                                   return newSize;
                                               }
                                           }));
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Prices prices = (Prices) o;
        return profileStart.equals(prices.profileStart) && profile.equals(prices.profile);
    }

    @Override
    public int hashCode() {
        int result = profileStart.hashCode();
        result = 31 * result + profile.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return toString(128);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, '{');
        Append.to(appendable, profileStart);
        Append.to(appendable, "..");
        Append.to(appendable, profileEnd());
        Append.to(appendable, ", ");
        Append.to(appendable, profile);
        Append.to(appendable, '}');
    }
}
