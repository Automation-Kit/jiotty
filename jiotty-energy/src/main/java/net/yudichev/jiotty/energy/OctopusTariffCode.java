package net.yudichev.jiotty.energy;

/// Parsing of Octopus electricity tariff codes, whose shape is `<fuel>-<rateType>-<productCode>-<region>` — e.g. `E-1R-AGILE-24-10-01-A`
/// (product `AGILE-24-10-01`, region `A`) or `E-FLAT2R-SILVER-23-12-06-A` (product `SILVER-23-12-06`). The rate-type segment is **not** fixed-width — `1R`,
/// `2R`, `FLAT2R` all occur — so the product code is everything between the second and the last dash, not a fixed-offset substring.
public final class OctopusTariffCode {
    private OctopusTariffCode() {
    }

    /// The product code embedded in a tariff code: the segments between the rate-type and the region — i.e. the text after the second dash and before the last
    /// dash (`E-1R-AGILE-24-10-01-A` → `AGILE-24-10-01`, `E-FLAT2R-SILVER-23-12-06-A` → `SILVER-23-12-06`). Lenient: a code without the expected dash structure
    /// is returned unchanged (it will simply fail to resolve at Octopus, which the caller handles), so this never throws on unexpected input.
    public static String productCode(String tariffCode) {
        int firstDash = tariffCode.indexOf('-');
        int secondDash = firstDash < 0 ? -1 : tariffCode.indexOf('-', firstDash + 1);
        int lastDash = tariffCode.lastIndexOf('-');
        return secondDash > 0 && lastDash > secondDash ? tariffCode.substring(secondDash + 1, lastDash) : tariffCode;
    }
}
