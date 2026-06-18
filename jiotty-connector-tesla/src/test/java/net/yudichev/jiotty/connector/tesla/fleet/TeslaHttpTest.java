package net.yudichev.jiotty.connector.tesla.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Covers the shared partner-endpoint response unwrapping used by [TeslaFleetPartnerImpl] via [TeslaHttp].
class TeslaHttpTest {
    @Test
    void unwrapOrFailReturnsResponseWhenPresent() {
        PartnerAccount account = PartnerAccount.builder().setName("My App").setDomain("example.com").build();
        var wrapper = ResponseWrapper.builder().setResponse(account).build();
        assertThat(TeslaHttp.unwrapOrFail().apply(wrapper)).isEqualTo(account);
    }

    @Test
    void unwrapOrFailThrowsErrorTextWhenResponseAbsent() {
        var wrapper = ResponseWrapper.<PartnerAccount>builder().setError("domain not registered").build();
        assertThatThrownBy(() -> TeslaHttp.<PartnerAccount>unwrapOrFail().apply(wrapper))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("domain not registered");
    }
}
