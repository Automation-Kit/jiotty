package net.yudichev.jiotty.energy.octopus;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static java.lang.Math.floorMod;

public final class OctopusPriceSlots {
    /// The time zone of Octopus' rates, and of the time bands of a time-of-use tariff, wherever the process runs.
    public static final ZoneId UK_ZONE = ZoneId.of("Europe/London");
    /// The length of one price slot, in seconds: every price that an [OctopusTariffPriceService] publishes covers one half hour.
    public static final int SLOT_LENGTH_SEC = 30 * 60;
    public static final Duration SLOT_LENGTH = Duration.ofSeconds(SLOT_LENGTH_SEC);
    /// The delay before a failed fetch of prices, or a failed resolution of a tariff through [OctopusTariffPriceServiceRegistry#forTariff], is tried again.
    public static final Duration FAILURE_RETRY_DELAY = Duration.ofMinutes(15);

    private OctopusPriceSlots() {
    }

    /// @return the start of the slot running at `t`
    public static Instant floorToSlot(Instant t) {
        long sec = t.getEpochSecond();
        return Instant.ofEpochSecond(sec - floorMod(sec, SLOT_LENGTH_SEC));
    }

    /// @return the first instant strictly after `now` at which [#UK_ZONE] clocks show `timeOfDay`
    public static Instant nextUkTimeOfDayAfter(Instant now, LocalTime timeOfDay) {
        ZonedDateTime localNow = now.atZone(UK_ZONE);
        ZonedDateTime today = localNow.toLocalDate().atTime(timeOfDay).atZone(UK_ZONE);
        return (localNow.isBefore(today) ? today : today.plusDays(1)).toInstant();
    }
}
