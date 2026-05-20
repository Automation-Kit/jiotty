package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/// Full payload from `/products/{code}/`. The tariff maps are keyed first by region (Octopus uses an underscore-prefixed letter, e.g. `_A`) and then by payment
/// method (`direct_debit_monthly`, `direct_debit_quarterly`, `non_direct_debit`, `varying`).
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

    @JsonProperty("single_register_electricity_tariffs")
    Map<String, Map<String, TariffVariant>> singleRegisterElectricityTariffs();
}
