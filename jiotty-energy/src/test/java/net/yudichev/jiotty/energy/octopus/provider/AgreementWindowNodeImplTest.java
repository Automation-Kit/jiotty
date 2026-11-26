package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.connector.octopusenergy.AccountProperty;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeterPoint;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Loaded;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Unavailable;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeAccountNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static net.yudichev.jiotty.energy.octopus.provider.ProviderTestFixtures.createAgreement;
import static org.assertj.core.api.Assertions.assertThat;

class AgreementWindowNodeImplTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    private static final Duration FORWARD_WINDOW = Duration.ofDays(1);
    private static final Tariff PAST = createAgreement("E-1R-PAST-A", START.minus(Duration.ofDays(30)), START.minus(Duration.ofDays(10)));
    private static final Tariff CURRENT = createAgreement("E-1R-CURRENT-A", START.minus(Duration.ofDays(10)), START.plus(Duration.ofHours(6)));
    private static final Tariff NEXT = createAgreement("E-1R-NEXT-A", START.plus(Duration.ofHours(6)), START.plus(FORWARD_WINDOW).plus(Duration.ofHours(2)));
    private static final Tariff LATER = createAgreement("E-1R-LATER-A", START.plus(FORWARD_WINDOW).plus(Duration.ofHours(2)), null);

    private final TestGraph graph = TestGraph.startingAt(START);
    private FakeAccountNode accountNode;
    private AgreementWindowNodeImpl node;

    @BeforeEach
    void setUp() {
        accountNode = new FakeAccountNode(graph.runner());
        accountNode.registerInGraph();
        node = new AgreementWindowNodeImpl(FORWARD_WINDOW, graph.runner(), accountNode);
        node.registerInGraph();
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void untilTheAccountLoads_theAgreementsAreUnknown() {
        graph.runWave();
        assertThat(node.agreements()).isNull();

        accountNode.set(new Unavailable(new RuntimeException("octopus is down")));
        graph.runWave();
        assertThat(node.agreements()).isNull();
    }

    @Test
    void loadedAccount_listsTheImportAgreementsOverlappingTheWindow_inTheOrderOfTheirStart() {
        accountNode.set(new Loaded(createAccount(LATER, NEXT, PAST, CURRENT)));
        graph.runWave();

        assertThat(node.agreements()).containsExactly(CURRENT, NEXT);
    }

    @Test
    void exportMeterPoint_isSkipped() {
        accountNode.set(new Loaded(OctopusAccountData.builder()
                                                     .addProperties(AccountProperty.builder()
                                                                                   .addElectricityMeterPoints(createMeterPoint(true, NEXT))
                                                                                   .addElectricityMeterPoints(createMeterPoint(false, CURRENT))
                                                                                   .build())
                                                     .build()));
        graph.runWave();

        assertThat(node.agreements()).containsExactly(CURRENT);
    }

    @Test
    void accountWithNoProperty_hasNoAgreements() {
        accountNode.set(new Loaded(OctopusAccountData.builder().build()));
        graph.runWave();

        assertThat(node.agreements()).isEmpty();
    }

    @Test
    void propertyWithNoImportMeterPoint_hasNoAgreements() {
        accountNode.set(new Loaded(OctopusAccountData.builder()
                                                     .addProperties(AccountProperty.builder()
                                                                                   .addElectricityMeterPoints(createMeterPoint(true, CURRENT))
                                                                                   .build())
                                                     .build()));
        graph.runWave();

        assertThat(node.agreements()).isEmpty();
    }

    @Test
    void agreementThatEnds_leavesTheListAtItsEnd() {
        accountNode.set(new Loaded(createAccount(CURRENT, NEXT)));
        graph.runWave();

        graph.clock().setTimeAndTick(CURRENT.validTo().orElseThrow().minusNanos(1));
        assertThat(node.agreements()).containsExactly(CURRENT, NEXT);

        graph.clock().setTimeAndTick(CURRENT.validTo().orElseThrow());
        assertThat(node.agreements()).containsExactly(NEXT);
    }

    @Test
    void laterAgreement_entersTheListWhenTheWindowFirstOverlapsIt() {
        accountNode.set(new Loaded(createAccount(CURRENT, NEXT, LATER)));
        graph.runWave();
        Instant entry = LATER.validFrom().minus(FORWARD_WINDOW).plusNanos(1);

        graph.clock().setTimeAndTick(entry.minusNanos(1));
        assertThat(node.agreements()).containsExactly(CURRENT, NEXT);

        graph.clock().setTimeAndTick(entry);
        assertThat(node.agreements()).containsExactly(CURRENT, NEXT, LATER);
    }

    private static OctopusAccountData createAccount(Tariff... agreements) {
        return OctopusAccountData.builder()
                                 .addProperties(AccountProperty.builder().addElectricityMeterPoints(createMeterPoint(false, agreements)).build())
                                 .build();
    }

    private static ElectricityMeterPoint createMeterPoint(boolean export, Tariff... agreements) {
        return ElectricityMeterPoint.builder().setMpan("9999999999999").addTariffs(agreements).setExport(export).build();
    }
}
