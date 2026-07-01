/*
 * Copyright (c) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package net.yudichev.jiotty.common.lang.backoff;

import com.google.common.base.Preconditions;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

import java.util.function.DoubleSupplier;

import static com.google.common.base.Preconditions.checkNotNull;

/// Implementation of [BackOff] that increases the back off period for each retry attempt using a randomization function that grows exponentially.
///
/// [#nextBackOffMillis()] is calculated using the following formula:
/// <pre>
/// randomized_interval =
/// retry_interval * (random value in range [1-randomization_factor,1+randomization_factor])
/// </pre>
///
/// In other words [#nextBackOffMillis()] will range between the randomization factor percentage below and above the retry interval. For example, using 2
/// seconds as the base retry interval and 0.5 as the randomization factor, the actual back off period used in the next retry attempt will be between 1 and 3
/// seconds.
///
/// **Note:** max_interval caps the retry_interval and not the randomized_interval.
///
/// If the time elapsed since an [ExponentialBackOff] instance is created goes past the max_elapsed_time then the method [#nextBackOffMillis()] starts returning
/// [BackOff#STOP]. The elapsed time can be reset by calling [#reset()].
///
/// Example: The default retry_interval is .5 seconds, default randomization_factor is 0.5, default multiplier is 1.5 and the default max_interval is 1 minute.
/// For 10 tries the sequence will be (values in seconds) and assuming we go over the max_elapsed_time on the 10th try:
/// <pre>
/// request#     retry_interval     randomized_interval
///
/// 1             0.5                [0.25,0.75]
/// 2             0.75               [0.375,1.125]
/// 3             1.125              [0.562,1.687]
/// 4             1.687              [0.8435,2.53]
/// 5             2.53               [1.265,3.795]
/// 6             3.795              [1.897,5.692]
/// 7             5.692              [2.846,8.538]
/// 8             8.538              [4.269,12.807]
/// 9            12.807              [6.403,19.210]
/// 10           19.210              [BackOff#STOP]
/// </pre>
///
/// Implementation is not thread-safe.
///
/// @author Ravi Mistry
public class ExponentialBackOff implements BackOff, StringFormattable {

    /// The default initial interval value in milliseconds (0.5 seconds).
    public static final long DEFAULT_INITIAL_INTERVAL_MILLIS = 500;

    /// The default randomization factor (0.5 which results in a random period ranging between 50% below and 50% above the retry interval).
    public static final double DEFAULT_RANDOMIZATION_FACTOR = 0.5;

    /// The default multiplier value (1.5 which is 50% increase per back off).
    public static final double DEFAULT_MULTIPLIER = 1.5;

    /// The default maximum back off time in milliseconds (1 minute).
    public static final long DEFAULT_MAX_INTERVAL_MILLIS = 60000;

    /// The default maximum elapsed time in milliseconds (15 minutes).
    public static final long DEFAULT_MAX_ELAPSED_TIME_MILLIS = 900000;
    private final DoubleSupplier rng;
    /// The initial retry interval in milliseconds.
    private final long initialIntervalMillis;
    /// The randomization factor to use for creating a range around the retry interval.
    ///
    /// A randomization factor of 0.5 results in a random period ranging between 50% below and 50% above the retry interval.
    private final double randomizationFactor;
    /// The value to multiply the current interval with for each retry attempt.
    private final double multiplier;
    /// The maximum value of the back off period in milliseconds. Once the retry interval reaches this value it stops increasing.
    private final long maxIntervalMillis;
    /// The maximum elapsed time after instantiating [ExponentialBackOff] or calling [#reset()] after which [#nextBackOffMillis()] returns [BackOff#STOP].
    private final long maxElapsedTimeMillis;
    /// Nano clock.
    private final NanoClock nanoClock;
    /// The system time in nanoseconds. It is calculated when an ExponentialBackOffPolicy instance is created and is reset when [#reset()] is called.
    private long startTimeNanos;
    /// The current retry interval in milliseconds.
    private double currentIntervalMillis;

    /// Creates an instance of ExponentialBackOffPolicy using default values.
    ///
    /// To override the defaults use [Builder].
    ///
    ///     - `initialIntervalMillis` defaults to [#DEFAULT_INITIAL_INTERVAL_MILLIS]
    ///   - `randomizationFactor` defaults to [#DEFAULT_RANDOMIZATION_FACTOR]
    ///   - `multiplier` defaults to [#DEFAULT_MULTIPLIER]
    ///   - `maxIntervalMillis` defaults to [#DEFAULT_MAX_INTERVAL_MILLIS]
    ///   - `maxElapsedTimeMillis` defaults in [#DEFAULT_MAX_ELAPSED_TIME_MILLIS]
    ///
    public ExponentialBackOff() {
        this(new Builder());
    }

    /// @param builder builder
    protected ExponentialBackOff(Builder builder) {
        initialIntervalMillis = builder.initialIntervalMillis;
        randomizationFactor = builder.randomizationFactor;
        multiplier = builder.multiplier;
        maxIntervalMillis = builder.maxIntervalMillis;
        maxElapsedTimeMillis = builder.maxElapsedTimeMillis;
        nanoClock = builder.nanoClock;
        rng = builder.rng;
        Preconditions.checkArgument(initialIntervalMillis > 0);
        Preconditions.checkArgument(0 <= randomizationFactor && randomizationFactor < 1);
        Preconditions.checkArgument(multiplier >= 1);
        Preconditions.checkArgument(maxIntervalMillis >= initialIntervalMillis);
        Preconditions.checkArgument(maxElapsedTimeMillis > 0);
        reset();
    }

    /// Sets the interval back to the initial retry interval and restarts the timer.
    @Override
    public final void reset() {
        currentIntervalMillis = initialIntervalMillis;
        startTimeNanos = nanoClock.nanoTime();
    }

    /// {@inheritDoc}
    ///
    /// This method calculates the next back off interval using the formula: randomized_interval = retry_interval +/- (randomization_factor * retry_interval)
    ///
    /// Subclasses may override if a different algorithm is required.
    @Override
    public long nextBackOffMillis() {
        // Make sure we have not gone over the maximum elapsed time.
        if (getElapsedTimeMillis() > maxElapsedTimeMillis) {
            return STOP;
        }
        long randomizedInterval =
                getRandomValueFromInterval(randomizationFactor, rng.getAsDouble(), currentIntervalMillis);
        incrementCurrentInterval();
        return randomizedInterval;
    }

    /// Returns the initial retry interval in milliseconds.
    public final long getInitialIntervalMillis() {
        return initialIntervalMillis;
    }

    /// Returns the randomization factor to use for creating a range around the retry interval.
    ///
    /// A randomization factor of 0.5 results in a random period ranging between 50% below and 50% above the retry interval.
    public final double getRandomizationFactor() {
        return randomizationFactor;
    }

    /// Returns the current retry interval in milliseconds.
    public final long getCurrentIntervalMillis() {
        //noinspection NumericCastThatLosesPrecision
        return (long) currentIntervalMillis;
    }

    /// Returns the value to multiply the current interval with for each retry attempt.
    public final double getMultiplier() {
        return multiplier;
    }

    /// Returns the maximum value of the back off period in milliseconds. Once the current interval reaches this value it stops increasing.
    public final long getMaxIntervalMillis() {
        return maxIntervalMillis;
    }

    @Override
    public final long getMaxElapsedTimeMillis() {
        return maxElapsedTimeMillis;
    }

    /// Returns the elapsed time in milliseconds since an [ExponentialBackOff] instance is created and is reset when [#reset()] is called.
    ///
    /// The elapsed time is computed using [System#nanoTime()].
    public final long getElapsedTimeMillis() {
        return (nanoClock.nanoTime() - startTimeNanos) / 1000000;
    }

    /// Returns a random value from the interval `[randomizationFactor*currentInterval, randomizationFactor * currentInterval]`.
    static long getRandomValueFromInterval(
            double randomizationFactor, double random, double currentIntervalMillis) {
        double delta = randomizationFactor * currentIntervalMillis;
        double minInterval = currentIntervalMillis - delta;
        double maxInterval = currentIntervalMillis + delta;
        // Get a random value from the range [minInterval, maxInterval].
        // The formula used below has a +1 because if the minInterval is 1 and the maxInterval is 3 then
        // we want a 33% chance for selecting either 1, 2 or 3.
        //noinspection NumericCastThatLosesPrecision
        return (long) (minInterval + (random * (maxInterval - minInterval + 1)));
    }

    /// Increments the current interval by multiplying it with the multiplier.
    private void incrementCurrentInterval() {
        // Check for overflow, if overflow is detected set the current interval to the max interval.
        if (currentIntervalMillis >= maxIntervalMillis / multiplier) {
            currentIntervalMillis = maxIntervalMillis;
        } else {
            currentIntervalMillis *= multiplier;
        }
    }

    @Override
    public String toString() {
        return toString(256);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "ExponentialBackOff{initialIntervalMillis=");
        Append.to(appendable, initialIntervalMillis);
        Append.to(appendable, ", randomizationFactor=");
        Append.to(appendable, randomizationFactor);
        Append.to(appendable, ", multiplier=");
        Append.to(appendable, multiplier);
        Append.to(appendable, ", maxIntervalMillis=");
        Append.to(appendable, maxIntervalMillis);
        Append.to(appendable, ", maxElapsedTimeMillis=");
        Append.to(appendable, maxElapsedTimeMillis);
        Append.to(appendable, ", startTimeNanos=");
        Append.to(appendable, startTimeNanos);
        Append.to(appendable, ", currentIntervalMillis=");
        Append.to(appendable, currentIntervalMillis);
        Append.to(appendable, '}');
    }

    /// Builder for [ExponentialBackOff].
    ///
    /// Implementation is not thread-safe.
    public static class Builder {
        private DoubleSupplier rng = Math::random;

        /// The initial retry interval in milliseconds.
        private long initialIntervalMillis = DEFAULT_INITIAL_INTERVAL_MILLIS;

        /// The randomization factor to use for creating a range around the retry interval.
        ///
        /// A randomization factor of 0.5 results in a random period ranging between 50% below and 50% above the retry interval.
        private double randomizationFactor = DEFAULT_RANDOMIZATION_FACTOR;

        /// The value to multiply the current interval with for each retry attempt.
        private double multiplier = DEFAULT_MULTIPLIER;

        /// The maximum value of the back off period in milliseconds. Once the retry interval reaches this value it stops increasing.
        private long maxIntervalMillis = DEFAULT_MAX_INTERVAL_MILLIS;

        /// The maximum elapsed time in milliseconds after instantiating [ExponentialBackOff] or calling [#reset()] after which [#nextBackOffMillis()] returns
        /// [BackOff#STOP].
        private long maxElapsedTimeMillis = DEFAULT_MAX_ELAPSED_TIME_MILLIS;

        /// Nano clock.
        private NanoClock nanoClock = NanoClock.SYSTEM;

        public Builder() {
        }

        public Builder(ExponentialBackoffConfig config) {
            initialIntervalMillis = config.initialIntervalMillis();
            randomizationFactor = config.randomizationFactor();
            multiplier = config.multiplier();
            maxIntervalMillis = config.maxIntervalMillis();
            maxElapsedTimeMillis = config.maxElapsedTimeMillis();
        }

        /// Builds a new instance of [ExponentialBackOff].
        public ExponentialBackOff build() {
            return new ExponentialBackOff(this);
        }

        public DoubleSupplier getRng() {
            return rng;
        }

        public Builder setRng(DoubleSupplier rng) {
            this.rng = checkNotNull(rng);
            return this;
        }

        /// Returns the initial retry interval in milliseconds. The default value is [#DEFAULT_INITIAL_INTERVAL_MILLIS].
        public final long getInitialIntervalMillis() {
            return initialIntervalMillis;
        }

        /// Sets the initial retry interval in milliseconds. The default value is [#DEFAULT_INITIAL_INTERVAL_MILLIS]. Must be `> 0`.
        ///
        /// Overriding is only supported for the purpose of calling the super implementation and changing the return type, but nothing else.
        public Builder setInitialIntervalMillis(long initialIntervalMillis) {
            this.initialIntervalMillis = initialIntervalMillis;
            return this;
        }

        /// Returns the randomization factor to use for creating a range around the retry interval. The default value is [#DEFAULT_RANDOMIZATION_FACTOR].
        ///
        /// A randomization factor of 0.5 results in a random period ranging between 50% below and 50% above the retry interval.
        ///
        /// Overriding is only supported for the purpose of calling the super implementation and changing the return type, but nothing else.
        public final double getRandomizationFactor() {
            return randomizationFactor;
        }

        /// Sets the randomization factor to use for creating a range around the retry interval. The default value is [#DEFAULT_RANDOMIZATION_FACTOR]. Must fall
        /// in the range `0 <= randomizationFactor < 1`.
        ///
        /// A randomization factor of 0.5 results in a random period ranging between 50% below and 50% above the retry interval.
        ///
        /// Overriding is only supported for the purpose of calling the super implementation and changing the return type, but nothing else.
        public Builder setRandomizationFactor(double randomizationFactor) {
            this.randomizationFactor = randomizationFactor;
            return this;
        }

        /// Returns the value to multiply the current interval with for each retry attempt. The default value is [#DEFAULT_MULTIPLIER].
        public final double getMultiplier() {
            return multiplier;
        }

        /// Sets the value to multiply the current interval with for each retry attempt. The default value is [#DEFAULT_MULTIPLIER]. Must be `>= 1`.
        ///
        /// Overriding is only supported for the purpose of calling the super implementation and changing the return type, but nothing else.
        public Builder setMultiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }

        /// Returns the maximum value of the back off period in milliseconds. Once the current interval reaches this value it stops increasing. The default
        /// value is [#DEFAULT_MAX_INTERVAL_MILLIS]. Must be `>= initialInterval`.
        public final long getMaxIntervalMillis() {
            return maxIntervalMillis;
        }

        /// Sets the maximum value of the back off period in milliseconds. Once the current interval reaches this value it stops increasing. The default value
        /// is [#DEFAULT_MAX_INTERVAL_MILLIS].
        ///
        /// Overriding is only supported for the purpose of calling the super implementation and changing the return type, but nothing else.
        public Builder setMaxIntervalMillis(long maxIntervalMillis) {
            this.maxIntervalMillis = maxIntervalMillis;
            return this;
        }

        /// Returns the maximum elapsed time in milliseconds. The default value is [#DEFAULT_MAX_ELAPSED_TIME_MILLIS].
        ///
        /// If the time elapsed since an [ExponentialBackOff] instance is created goes past the max_elapsed_time then the method [#nextBackOffMillis()] starts
        /// returning [BackOff#STOP]. The elapsed time can be reset by calling [#reset()].
        public final long getMaxElapsedTimeMillis() {
            return maxElapsedTimeMillis;
        }

        /// Sets the maximum elapsed time in milliseconds. The default value is [#DEFAULT_MAX_ELAPSED_TIME_MILLIS]. Must be `> 0`.
        ///
        /// If the time elapsed since an [ExponentialBackOff] instance is created goes past the max_elapsed_time then the method [#nextBackOffMillis()] starts
        /// returning [BackOff#STOP]. The elapsed time can be reset by calling [#reset()].
        ///
        /// Overriding is only supported for the purpose of calling the super implementation and changing the return type, but nothing else.
        public Builder setMaxElapsedTimeMillis(long maxElapsedTimeMillis) {
            this.maxElapsedTimeMillis = maxElapsedTimeMillis;
            return this;
        }

        /// Returns the nano clock.
        public final NanoClock getNanoClock() {
            return nanoClock;
        }

        /// Sets the nano clock ([NanoClock#SYSTEM] by default).
        ///
        /// Overriding is only supported for the purpose of calling the super implementation and changing the return type, but nothing else.
        public Builder setNanoClock(NanoClock nanoClock) {
            this.nanoClock = checkNotNull(nanoClock);
            return this;
        }
    }
}