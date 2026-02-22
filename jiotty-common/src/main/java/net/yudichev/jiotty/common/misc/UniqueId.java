package net.yudichev.jiotty.common.misc;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class UniqueId {
    static final long EPOCH_SECONDS = 1_767_225_600L;
    static final long MICROS_PER_SECOND = 1_000_000L;
    static final long NANOS_PER_MICRO = 1_000L;
    /// Enough for about 30 years
    static final int TIME_DIGITS = 15;
    static final int RANDOM_CHARS = 6;
    static final int TOTAL_LENGTH = TIME_DIGITS + RANDOM_CHARS + 1;
    private static final ThreadLocal<StringBuilder> BUILDER =
            ThreadLocal.withInitial(() -> new StringBuilder(TOTAL_LENGTH));
    private static final String RANDOM_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    static final int RANDOM_BASE = RANDOM_ALPHABET.length();
    static final long RANDOM_LIMIT = computeRandomLimit();

    /// @return an ID that is globally unique with a very high probability. The returned value starts with the provided prefix character, followed by a fixed
    /// number of numeric characters representing the number of microseconds since UTC midnight of 2026-01-01, and a fixed number of random lower-case
    /// alphanumeric characters.
    public static String generate(char prefix) {
        Instant now = Instant.now();
        long microsSinceEpoch = (now.getEpochSecond() - EPOCH_SECONDS) * MICROS_PER_SECOND + now.getNano() / NANOS_PER_MICRO;
        if (microsSinceEpoch < 0) {
            microsSinceEpoch = 0;
        }

        long random = ThreadLocalRandom.current().nextLong(RANDOM_LIMIT);

        var builder = BUILDER.get();
        builder.setLength(0);
        builder.append(prefix);
        appendFixedWidthDecimal(builder, microsSinceEpoch, TIME_DIGITS);
        appendFixedWidthBase36(builder, random, RANDOM_CHARS);
        return builder.toString();
    }

    @SuppressWarnings("CharUsedInArithmeticContext") // exactly as designed
    private static void appendFixedWidthDecimal(StringBuilder builder, long value, int digits) {
        int offset = builder.length();
        builder.setLength(offset + digits);
        long remaining = value;
        for (int index = offset + digits - 1; index >= offset; index--) {
            long digit = remaining % 10;
            builder.setCharAt(index, (char) ('0' + digit));
            remaining /= 10;
        }
    }

    private static void appendFixedWidthBase36(StringBuilder builder, long value, int digits) {
        int offset = builder.length();
        builder.setLength(offset + digits);
        long remaining = value;
        for (int index = offset + digits - 1; index >= offset; index--) {
            int digit = (int) (remaining % RANDOM_BASE);
            builder.setCharAt(index, RANDOM_ALPHABET.charAt(digit));
            remaining /= RANDOM_BASE;
        }
    }

    private static long computeRandomLimit() {
        long limit = 1;
        for (int index = 0; index < RANDOM_CHARS; index++) {
            limit *= RANDOM_BASE;
        }
        return limit;
    }
}
