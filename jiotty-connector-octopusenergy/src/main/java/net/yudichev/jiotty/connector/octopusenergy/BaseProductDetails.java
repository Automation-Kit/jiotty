package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/// Full payload from `/products/{code}/`. The tariff maps are keyed first by region (Octopus uses an underscore-prefixed letter, e.g. `_A`) and then by payment
/// method ({@value OctopusEnergy#DIRECT_DEBIT_MONTHLY_PAYMENT_METHOD}, `direct_debit_quarterly`, `non_direct_debit`, `varying`).
@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseProductDetails {
    String code();

    @JsonProperty("display_name")
    String displayName();

    String brand();

    @JsonProperty("available_from")
    Instant availableFrom();

    @JsonProperty("available_to")
    Optional<Instant> availableTo();

    @JsonProperty("tariffs_active_at")
    Instant tariffsActiveAt();

    /// Whether the product is a Tracker, whose unit rate changes every day with the wholesale price.
    @JsonProperty("is_tracker")
    @Value.Default
    default boolean tracker() {
        return false;
    }

    @JsonProperty("single_register_electricity_tariffs")
    Map<String, Map<String, TariffVariant>> singleRegisterElectricityTariffs();

    /// Tariffs for a meter with separate day and night registers (Economy 7); their unit rates come from [OctopusRegionService#getDayUnitRates] and
    /// [OctopusRegionService#getNightUnitRates].
    @JsonProperty("dual_register_electricity_tariffs")
    Map<String, Map<String, TariffVariant>> dualRegisterElectricityTariffs();

    /// Tariffs with day, night and EV-device rates on a single meter, as on the Intelligent Octopus Go `IOG-SMB-` products; their unit rates come from
    /// [OctopusRegionService#getDayUnitRates] and [OctopusRegionService#getNightUnitRates], never from [OctopusRegionService#getStandardUnitRates].
    @JsonProperty("four_rate_ev_electricity_tariffs")
    Map<String, Map<String, TariffVariant>> fourRateEvElectricityTariffs();
}
