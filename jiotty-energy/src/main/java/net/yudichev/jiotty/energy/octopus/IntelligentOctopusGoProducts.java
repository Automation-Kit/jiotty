package net.yudichev.jiotty.energy.octopus;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;

/// The Intelligent Octopus Go products, on which Octopus schedules the charging of the customer's car.
public final class IntelligentOctopusGoProducts {
    /// The time zone of [#OFF_PEAK_START] and [#OFF_PEAK_END]: the window follows UK clock time.
    public static final ZoneId ZONE = OctopusPriceSlots.UK_ZONE;
    /// The start of the fixed off-peak window, in [#ZONE].
    public static final LocalTime OFF_PEAK_START = LocalTime.of(23, 30);
    /// The end of the fixed off-peak window, in [#ZONE], on the day after [#OFF_PEAK_START].
    public static final LocalTime OFF_PEAK_END = LocalTime.of(5, 30);
    /// Found in the upper-cased product code of the older Intelligent Octopus products (`INTELLI-VAR-24-10-29`).
    public static final String INTELLIGENT_CODE_TOKEN = "INTELLI";
    /// Found in the upper-cased product code of the Intelligent Octopus Go `IOG-SMB-` products (`IOG-SMB-VAR-26-11-04`, `IOG-SMB-FIX-OEV-12M-26-10-01`).
    public static final String GO_CODE_TOKEN = "IOG";
    /// Found in the upper-cased product name of every Intelligent Octopus product, Go or not.
    public static final String INTELLIGENT_NAME_TOKEN = "INTELLIGENT OCTOPUS";
    /// Found in the upper-cased product name of the Go products ("Intelligent Octopus Go"); "Go" always follows a space.
    public static final String GO_NAME_TOKEN = " GO";

    private IntelligentOctopusGoProducts() {
    }

    /// @return whether `instant` falls in the off-peak window, from [#OFF_PEAK_START] to [#OFF_PEAK_END] in [#ZONE]
    public static boolean isOffPeak(Instant instant) {
        LocalTime localTime = instant.atZone(ZONE).toLocalTime();
        return !localTime.isBefore(OFF_PEAK_START) || localTime.isBefore(OFF_PEAK_END);
    }

    /// @return whether the product with this code and name is an Intelligent Octopus Go product: it is an Intelligent Octopus product, by its code or its
    ///         name, and a Go one, by its code or its name. Intelligent Octopus Flux, where Octopus manages a home battery, is an Intelligent Octopus product
    ///         by its name but a Go one by neither its code nor its name, so it does not match.
    public static boolean isIntelligentOctopusGo(String productCode, String productName) {
        String code = productCode.toUpperCase(Locale.ROOT);
        String name = productName.toUpperCase(Locale.ROOT);
        // A Go product code also marks the product as an Intelligent one.
        return code.contains(GO_CODE_TOKEN)
               || (code.contains(INTELLIGENT_CODE_TOKEN) || name.contains(INTELLIGENT_NAME_TOKEN)) && name.contains(GO_NAME_TOKEN);
    }
}
