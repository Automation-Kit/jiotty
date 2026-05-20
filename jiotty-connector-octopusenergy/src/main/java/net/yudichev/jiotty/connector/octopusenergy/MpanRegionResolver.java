package net.yudichev.jiotty.connector.octopusenergy;

import com.google.common.collect.ImmutableBiMap;

import java.util.Optional;

/// Maps between an MPAN's leading distributor-id digits and the Octopus tariff region letter that appears as the trailing character of tariff codes (e.g.
/// `E-1R-AGILE-23-12-06-A` — the `A` is region Eastern). The table is the same one used in `agile_vs_go.py:59-63`; the gaps at `I` and `O` are deliberate (the
/// UK GB-DNO region set skips those letters).
public final class MpanRegionResolver {
    private static final ImmutableBiMap<String, Character> DISTRIBUTOR_TO_REGION = ImmutableBiMap.<String, Character>builder()
                                                                                                 .put("10", 'A')
                                                                                                 .put("11", 'B')
                                                                                                 .put("12", 'C')
                                                                                                 .put("13", 'D')
                                                                                                 .put("14", 'E')
                                                                                                 .put("15", 'F')
                                                                                                 .put("16", 'G')
                                                                                                 .put("17", 'H')
                                                                                                 .put("18", 'K')
                                                                                                 .put("19", 'J')
                                                                                                 .put("20", 'P')
                                                                                                 .put("21", 'L')
                                                                                                 .put("22", 'M')
                                                                                                 .put("23", 'N')
                                                                                                 .build();

    private MpanRegionResolver() {
    }

    /// Resolves an MPAN's region letter by inspecting its first two characters (the distributor id). Returns empty if the prefix doesn't match any known
    /// distributor or if the input is shorter than 2 characters.
    public static Optional<Character> resolveRegion(String mpan) {
        if (mpan == null || mpan.length() < 2) {
            return Optional.empty();
        }
        return Optional.ofNullable(DISTRIBUTOR_TO_REGION.get(mpan.substring(0, 2)));
    }

    /// True if the given character is a known Octopus tariff region letter. The closed set is A–P minus I and O.
    public static boolean isValidRegion(char regionLetter) {
        return DISTRIBUTOR_TO_REGION.containsValue(regionLetter);
    }
}
