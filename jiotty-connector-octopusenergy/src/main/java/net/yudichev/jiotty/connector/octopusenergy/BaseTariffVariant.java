package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.util.List;

/// One leaf of [BaseProductDetails#singleRegisterElectricityTariffs] etc. — describes a tariff variant for a specific region and payment method.
@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseTariffVariant {
    String code();

    @JsonProperty("standing_charge_exc_vat")
    double standingChargeExcVat();

    @JsonProperty("standing_charge_inc_vat")
    double standingChargeIncVat();

    List<ProductLink> links();
}
