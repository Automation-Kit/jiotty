package net.yudichev.jiotty.user.ui;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static net.yudichev.jiotty.user.ui.PreAuthAdmissionControl.Outcome.ADMITTED;
import static net.yudichev.jiotty.user.ui.PreAuthAdmissionControl.Outcome.RATE_LIMITED;
import static net.yudichev.jiotty.user.ui.PreAuthAdmissionControl.Outcome.VERIFY_SATURATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PreAuthAdmissionControlTest {
    private static final double PERMITS_PER_SECOND = 2.0;
    /// The rate cases must not trip over the in-flight bound, which they would since an admitted request holds its permit until released.
    private static final int UNCONSTRAINED_IN_FLIGHT = 1_000;

    private ProgrammableClock clock;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-07-20T12:00:00Z"));
    }

    /// A source spends its allowance, is refused, and is served again once the bucket has refilled.
    @Test
    void limitsEachSourceToItsRate(@Mock HttpServletRequest request) {
        var control = control(false);
        from(request, "10.0.0.1", null);

        assertThat(control.tryAdmit(request)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(request)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(request)).as("allowance spent").isEqualTo(RATE_LIMITED);

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        assertThat(control.tryAdmit(request)).as("refilled").isEqualTo(ADMITTED);
    }

    /// One source exhausting its allowance must not spend another's.
    @Test
    void tracksSourcesIndependently(@Mock HttpServletRequest noisy, @Mock HttpServletRequest other) {
        var control = control(false);
        from(noisy, "10.0.0.1", null);
        from(other, "10.0.0.2", null);

        control.tryAdmit(noisy);
        control.tryAdmit(noisy);
        assertThat(control.tryAdmit(noisy)).isEqualTo(RATE_LIMITED);

        assertThat(control.tryAdmit(other)).isEqualTo(ADMITTED);
    }

    /// Where no trusted proxy fronts the server, the header is caller-controlled: honouring it would let one source occupy an unlimited number of buckets and
    /// so escape the limit entirely.
    @Test
    void ignoresForwardedForWhenProxyHeadersAreNotTrusted(@Mock HttpServletRequest first,
                                                          @Mock HttpServletRequest second,
                                                          @Mock HttpServletRequest third) {
        var control = control(false);
        from(first, "10.0.0.1", "1.1.1.1");
        from(second, "10.0.0.1", "2.2.2.2");
        from(third, "10.0.0.1", "3.3.3.3");

        assertThat(control.tryAdmit(first)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(second)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(third))
                .as("same socket address, so the same bucket regardless of the header")
                .isEqualTo(RATE_LIMITED);
    }

    /// Where the header IS trusted, only its last entry was written by the proxy; earlier entries arrived from the caller and are forgeable, so prepending
    /// junk must not buy a fresh bucket.
    @Test
    void usesTheProxyWrittenEntryWhenProxyHeadersAreTrusted(@Mock HttpServletRequest first,
                                                            @Mock HttpServletRequest second,
                                                            @Mock HttpServletRequest third) {
        var control = control(true);
        from(first, "10.0.0.1", "spoofed-a, 9.9.9.9");
        from(second, "10.0.0.1", "spoofed-b, 9.9.9.9");
        from(third, "10.0.0.1", "spoofed-c, 9.9.9.9");

        assertThat(control.tryAdmit(first)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(second)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(third))
                .as("the forged prefix differs but the proxy-written entry is the same source")
                .isEqualTo(RATE_LIMITED);
    }

    /// A trusted-proxy deployment still has to bucket a request that arrives without the header, and a blank entry must not become a shared bucket name.
    @Test
    void fallsBackToTheSocketAddressWhenTheTrustedHeaderIsAbsentOrBlank(@Mock HttpServletRequest absent, @Mock HttpServletRequest blank) {
        var control = control(true);
        from(absent, "10.0.0.1", null);
        from(blank, "10.0.0.1", "   ");

        assertThat(control.tryAdmit(absent)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(blank)).as("both fall back to the same socket address").isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(absent)).isEqualTo(RATE_LIMITED);
    }

    /// A request with no resolvable address is bucketed under a shared fallback key.
    @Test
    void bucketsRequestsWithNoResolvableAddress(@Mock HttpServletRequest request) {
        var control = control(false);
        from(request, null, null);

        assertThat(control.tryAdmit(request)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(request)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(request)).isEqualTo(RATE_LIMITED);
    }

    /// Distinct real clients behind the proxy get their own allowance.
    @Test
    void separatesClientsBehindATrustedProxy(@Mock HttpServletRequest client, @Mock HttpServletRequest otherClient) {
        var control = control(true);
        from(client, "10.0.0.1", "9.9.9.9");
        from(otherClient, "10.0.0.1", "8.8.8.8");

        control.tryAdmit(client);
        control.tryAdmit(client);
        assertThat(control.tryAdmit(client)).isEqualTo(RATE_LIMITED);

        assertThat(control.tryAdmit(otherClient)).isEqualTo(ADMITTED);
    }

    /// Verification slots are global, so a rate-compliant flood from many sources still cannot pile up unbounded work — and a returned permit is reusable.
    @Test
    void boundsInFlightVerificationsAcrossSources(@Mock HttpServletRequest first,
                                                  @Mock HttpServletRequest second,
                                                  @Mock HttpServletRequest third,
                                                  @Mock HttpServletRequest fourth) {
        var control = control(false, 2);
        from(first, "10.0.0.1", null);
        from(second, "10.0.0.2", null);
        from(third, "10.0.0.3", null);
        from(fourth, "10.0.0.4", null);

        assertThat(control.tryAdmit(first)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(second)).isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(third))
                .as("within its own rate, but no verification slot free")
                .isEqualTo(VERIFY_SATURATED);

        control.releaseInFlight();
        assertThat(control.tryAdmit(fourth)).isEqualTo(ADMITTED);
    }

    /// The occupancy gauge and its limit are what the 80%-of-limit alert reads, so both must track the semaphore as permits are taken and returned.
    @Test
    void publishesInFlightOccupancyAgainstItsLimit(@Mock HttpServletRequest first, @Mock HttpServletRequest second) {
        var meterRegistry = new SimpleMeterRegistry();
        var control = new PreAuthAdmissionControl(clock, PERMITS_PER_SECOND, 4, false, meterRegistry);
        from(first, "10.0.0.1", null);
        from(second, "10.0.0.2", null);

        assertThat(gauge(meterRegistry, "preauth_verify_inflight_limit")).isEqualTo(4.0);
        assertThat(gauge(meterRegistry, "preauth_verify_inflight")).isZero();

        control.tryAdmit(first);
        control.tryAdmit(second);
        assertThat(gauge(meterRegistry, "preauth_verify_inflight")).isEqualTo(2.0);

        control.releaseInFlight();
        assertThat(gauge(meterRegistry, "preauth_verify_inflight")).isEqualTo(1.0);
    }

    /// Each rejection is counted under its own outcome, which is what distinguishes an abusive source from a saturated server on the dashboard.
    @Test
    void countsEachRejectionUnderItsOwnOutcome(@Mock HttpServletRequest request, @Mock HttpServletRequest otherSource) {
        var meterRegistry = new SimpleMeterRegistry();
        var control = new PreAuthAdmissionControl(clock, PERMITS_PER_SECOND, 1, false, meterRegistry);
        from(request, "10.0.0.1", null);
        from(otherSource, "10.0.0.2", null);

        control.tryAdmit(request);          // takes the only verification slot
        control.tryAdmit(request);          // second permit of this source's allowance, no slot free
        control.tryAdmit(request);          // allowance now spent

        assertThat(rejections(meterRegistry, "rate_limited")).isEqualTo(1.0);
        assertThat(rejections(meterRegistry, "verify_saturated")).isEqualTo(1.0);
        assertThat(control.tryAdmit(otherSource)).as("a fresh source is still refused while the pool is full").isEqualTo(VERIFY_SATURATED);
        assertThat(rejections(meterRegistry, "verify_saturated")).isEqualTo(2.0);
    }

    /// A verification pool that admits nothing would shed all traffic, so it is rejected at construction. (The rate bound is validated by the rate limiter.)
    @Test
    void rejectsNonPositiveInFlightBound() {
        assertThatThrownBy(() -> new PreAuthAdmissionControl(clock, 10.0, 0, false, new NoopMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max in-flight verifications");
    }

    private static double gauge(SimpleMeterRegistry meterRegistry, String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private static double rejections(SimpleMeterRegistry meterRegistry, String outcome) {
        return meterRegistry.get("guard_rejected_total").tag("guard", "pre_auth").tag("outcome", outcome).counter().count();
    }

    private PreAuthAdmissionControl control(boolean trustProxyHeaders) {
        return control(trustProxyHeaders, UNCONSTRAINED_IN_FLIGHT);
    }

    private PreAuthAdmissionControl control(boolean trustProxyHeaders, int maxInFlight) {
        return new PreAuthAdmissionControl(clock, PERMITS_PER_SECOND, maxInFlight, trustProxyHeaders, new NoopMeterRegistry());
    }

    private static void from(HttpServletRequest request, String remoteAddr, String forwardedFor) {
        // Lenient: whether either is read depends on whether the control under test trusts proxy headers.
        lenient().when(request.getRemoteAddr()).thenReturn(remoteAddr);
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
    }
}
