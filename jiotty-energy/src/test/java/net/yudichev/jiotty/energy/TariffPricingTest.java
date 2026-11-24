package net.yudichev.jiotty.energy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TariffPricingTest {
    @ParameterizedTest
    @EnumSource(TariffPricing.class)
    void isDynamic_onlyForDynamic(TariffPricing pricing) {
        assertThat(pricing.isDynamic()).isEqualTo(pricing == TariffPricing.DYNAMIC);
    }
}
