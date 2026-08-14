package net.yudichev.jiotty.connector.tesla.fleet;

import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.connector.tesla.fleet.TeslaHttp.withVinsRedacted;
import static org.assertj.core.api.Assertions.assertThat;

class TeslaHttpTest {
    @Test
    void redactsAVinInARequestPath() {
        assertThat(withVinsRedacted("https://vehicle-command:4443/api/1/vehicles/LRWYHCEK2NC222579/command/charge_stop").toString(96))
                .isEqualTo("https://vehicle-command:4443/api/1/vehicles/LRW…/command/charge_stop")
                .doesNotContain("222579");
    }

    @Test
    void redactsEveryVinInABody() {
        assertThat(withVinsRedacted("{\"vins\":[\"LRWYHCEK2NC222579\",\"XP7YHCEK9TB885083\"]}").toString(64))
                .isEqualTo("{\"vins\":[\"LRW…\",\"XP7…\"]}")
                .doesNotContain("222579")
                .doesNotContain("885083");
    }

    @Test
    void leavesTextCarryingNoVinUnchanged() {
        assertThat(withVinsRedacted("https://fleet-api.tesla.com/oauth2/v3/token").toString(64))
                .isEqualTo("https://fleet-api.tesla.com/oauth2/v3/token");
    }
}
