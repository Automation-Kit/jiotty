package net.yudichev.jiotty.energy.octopus.provider;

import com.google.common.base.VerifyException;
import net.yudichev.jiotty.energy.EnergyPriceService;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.AgreementInWindow;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.JoinedPrices;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.TariffPriceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static net.yudichev.jiotty.energy.octopus.TestPrices.FORWARD_WINDOW;
import static net.yudichev.jiotty.energy.octopus.TestPrices.FORWARD_WINDOW_SLOTS;
import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AgreementPricesTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Instant SWITCH = Instant.parse("2026-11-22T00:00:00Z");
    private static final Instant WINDOW_END = NOW.plus(FORWARD_WINDOW);
    private static final Instant PAST = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant REFRESH = Instant.parse("2026-11-21T18:00:00Z");
    private static final RuntimeException RESOLUTION_FAILURE = new RuntimeException("down");

    @Test
    void noAgreementInForce_isARetrievalErrorWithTheFixedMessage() {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(SWITCH, WINDOW_END, "T", createPricedState(createFlatPrices(NOW, 10, 1.0))));

        assertThat(joinedPrices.result()).hasValueSatisfying(result -> assertThat(result).isInstanceOfSatisfying(Failure.PriceRetrievalError.class, error -> {
            assertThat(error.cause()).hasMessage(AgreementPrices.NO_AGREEMENT_IN_FORCE);
        }));
    }

    static Stream<Arguments> currentAgreementsWithAFailure() {
        return Stream.of(
                // an unsupported tariff is an incompatible tariff
                arguments(TariffPriceState.Unsupported.INSTANCE, new Failure.IncompatibleTariff("T")),
                // a tariff that failed to resolve is a retrieval error with the cause
                arguments(new TariffPriceState.ResolutionFailed(RESOLUTION_FAILURE), new Failure.PriceRetrievalError(RESOLUTION_FAILURE)));
    }

    @ParameterizedTest
    @MethodSource("currentAgreementsWithAFailure")
    void currentAgreementWithoutPrices_isItsFailure(TariffPriceState priceState, Failure expectedFailure) {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(PAST, WINDOW_END, "T", priceState));

        assertThat(joinedPrices.result()).contains(expectedFailure);
    }

    @Test
    void currentAgreementResolving_publishesNothingYet() {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(PAST, WINDOW_END, "T", TariffPriceState.Resolving.INSTANCE));

        assertThat(joinedPrices.result()).isEmpty();
    }

    @Test
    void currentServiceFailing_isItsFailure() {
        var failure = new Failure.PriceRetrievalError(new RuntimeException("down"));
        JoinedPrices joinedPrices =
                joinNow(new AgreementInWindow(PAST, WINDOW_END, "T", new TariffPriceState.Priced(Optional.of(failure), Optional.of(REFRESH), false)));

        assertThat(joinedPrices.result()).contains(failure);
        assertThat(joinedPrices.nextRefreshTime()).contains(REFRESH);
    }

    @Test
    void contiguousAgreements_areJoinedAtTheSwitch() {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(PAST, SWITCH, "A", createPricedState(createFlatPrices(NOW, 24, 1.0))),
                                            new AgreementInWindow(SWITCH,
                                                                  WINDOW_END,
                                                                  "B",
                                                                  createPricedState(createFlatPrices(NOW, FORWARD_WINDOW_SLOTS, 2.0))));

        assertThat(joinedPrices.result()).hasValueSatisfying(result -> assertThat(result).isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profileStart()).isEqualTo(NOW);
            assertThat(prices.profile().pricePerInterval()).hasSize(FORWARD_WINDOW_SLOTS);
            assertThat(prices.profile().pricePerInterval().get(23)).isEqualTo(1.0);
            assertThat(prices.profile().pricePerInterval().get(24)).isEqualTo(2.0);
            assertThat(prices.profile().idxOfPredictedPriceStart()).isEqualTo(FORWARD_WINDOW_SLOTS);
        }));
    }

    @Test
    void pricesShortOfTheAgreementEnd_endTheProfile_withTheirRefreshTime() {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(PAST,
                                                                  SWITCH,
                                                                  "A",
                                                                  new TariffPriceState.Priced(Optional.of(createFlatPrices(NOW, 10, 1.0)),
                                                                                              Optional.of(REFRESH),
                                                                                              false)),
                                            new AgreementInWindow(SWITCH,
                                                                  WINDOW_END,
                                                                  "B",
                                                                  createPricedState(createFlatPrices(NOW, FORWARD_WINDOW_SLOTS, 2.0))));

        assertThat(joinedPrices.result()).hasValueSatisfying(result -> assertThat(result).isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profile().pricePerInterval()).hasSize(10);
        }));
        assertThat(joinedPrices.nextRefreshTime()).contains(REFRESH);
    }

    static Stream<Arguments> agreementsThatEndTheProfileAtTheSwitch() {
        return Stream.of(
                // the next agreement is unsupported, so no refresh is expected
                arguments(new AgreementInWindow(SWITCH, WINDOW_END, "B", TariffPriceState.Unsupported.INSTANCE), EnergyPriceService.NO_REFRESH_EXPECTED),
                // the next agreement is still resolving, so a retry is expected soon
                arguments(new AgreementInWindow(SWITCH, WINDOW_END, "B", TariffPriceState.Resolving.INSTANCE), NOW.plus(OctopusPriceSlots.FAILURE_RETRY_DELAY)),
                // the next agreement failed to resolve, so a retry is expected soon
                arguments(new AgreementInWindow(SWITCH, WINDOW_END, "B", new TariffPriceState.ResolutionFailed(RESOLUTION_FAILURE)),
                          NOW.plus(OctopusPriceSlots.FAILURE_RETRY_DELAY)),
                // the next agreement's service has delivered no prices yet, so its own refresh time is expected
                arguments(new AgreementInWindow(SWITCH, WINDOW_END, "B", new TariffPriceState.Priced(Optional.empty(), Optional.of(REFRESH), false)), REFRESH),
                // the next agreement's prices start after the agreement does, so its own refresh time is expected
                arguments(new AgreementInWindow(SWITCH,
                                                WINDOW_END,
                                                "B",
                                                new TariffPriceState.Priced(Optional.of(createFlatPrices(SWITCH.plus(Duration.ofHours(1)), 10, 2.0)),
                                                                            Optional.of(REFRESH),
                                                                            false)),
                          REFRESH),
                // a gap separates the agreements, so no refresh is expected
                arguments(new AgreementInWindow(SWITCH.plus(Duration.ofDays(1)),
                                                WINDOW_END,
                                                "B",
                                                createPricedState(createFlatPrices(NOW, FORWARD_WINDOW_SLOTS, 2.0))),
                          EnergyPriceService.NO_REFRESH_EXPECTED));
    }

    @ParameterizedTest
    @MethodSource("agreementsThatEndTheProfileAtTheSwitch")
    void nextAgreementWithoutJoinablePrices_endsTheProfileAtTheSwitch(AgreementInWindow nextAgreement, Instant expectedNextRefreshTime) {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(PAST, SWITCH, "A", createPricedState(createFlatPrices(NOW, 100, 1.0))), nextAgreement);

        assertThat(joinedPrices.result()).hasValueSatisfying(result -> assertThat(result).isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profileEnd()).isEqualTo(SWITCH);
        }));
        assertThat(joinedPrices.nextRefreshTime()).contains(expectedNextRefreshTime);
    }

    @Test
    void lastAgreementEndingInsideTheWindow_expectsNoRefresh() {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(PAST, SWITCH, "A", createPricedState(createFlatPrices(NOW, 100, 1.0))));

        assertThat(joinedPrices.nextRefreshTime()).contains(EnergyPriceService.NO_REFRESH_EXPECTED);
    }

    @Test
    void dynamicAgreement_takesTheForecastAfterItsActualPrices_andFlagsTheRestPredicted() {
        TariffPriceState agilePriceState = new TariffPriceState.Priced(Optional.of(createFlatPrices(NOW, 4, 1.0)), Optional.empty(), true);
        List<AgreementInWindow> agreements = List.of(new AgreementInWindow(PAST, SWITCH, "A", agilePriceState),
                                                     new AgreementInWindow(SWITCH,
                                                                           WINDOW_END,
                                                                           "B",
                                                                           createPricedState(createFlatPrices(NOW, FORWARD_WINDOW_SLOTS, 2.0))));

        JoinedPrices joinedPrices = AgreementPrices.join(agreements, NOW, WINDOW_END, Optional.of(createFlatPrices(NOW, 48, 3.0)));

        assertThat(joinedPrices.result()).hasValueSatisfying(result -> assertThat(result).isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profile().pricePerInterval().get(3)).isEqualTo(1.0);
            assertThat(prices.profile().pricePerInterval().get(4)).isEqualTo(3.0);
            assertThat(prices.profile().pricePerInterval().get(24)).isEqualTo(2.0);
            assertThat(prices.profile().idxOfPredictedPriceStart())
                    .describedAs("the predicted prices start at the first forecast slot, and the published stretch after the forecast is predicted")
                    .isEqualTo(4);
        }));
    }

    @Test
    void agreementInForce_isFoundPastOneThatHasEnded() {
        JoinedPrices joinedPrices = joinNow(new AgreementInWindow(PAST, NOW, "A", TariffPriceState.Unsupported.INSTANCE),
                                            new AgreementInWindow(NOW, WINDOW_END, "B", createPricedState(createFlatPrices(NOW, FORWARD_WINDOW_SLOTS, 2.0))));

        assertThat(joinedPrices.result()).hasValueSatisfying(result -> assertThat(result).isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profileStart()).isEqualTo(NOW);
        }));
    }

    @Test
    void pricesWithDifferentIntervalLengths_areRejected() {
        Prices hourlyPrices = new Prices(NOW, new PriceProfile(2 * OctopusPriceSlots.SLOT_LENGTH_SEC, 100, Collections.nCopies(100, 2.0)));

        assertThatThrownBy(() -> joinNow(new AgreementInWindow(PAST, SWITCH, "A", createPricedState(createFlatPrices(NOW, 24, 1.0))),
                                         new AgreementInWindow(SWITCH, WINDOW_END, "B", createPricedState(hourlyPrices))))
                .isInstanceOf(VerifyException.class)
                .hasMessageContaining("cannot join prices with different interval lengths");
    }

    private static JoinedPrices joinNow(AgreementInWindow... agreements) {
        return AgreementPrices.join(List.of(agreements), NOW, WINDOW_END, Optional.empty());
    }

    private static TariffPriceState createPricedState(Prices prices) {
        return new TariffPriceState.Priced(Optional.of(prices), Optional.empty(), false);
    }
}
