package net.yudichev.jiotty.energy.octopus.provider;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.TariffPeriod;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolved;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/// A [TariffTimelineNode] that makes a period of each agreement in the forward window, priced as its tariff resolves.
final class TariffTimelineNodeImpl extends BaseServerNode implements TariffTimelineNode {
    private final AgreementWindowNode agreementWindowNode;
    private final TariffSourcesNode tariffSourcesNode;

    private @Nullable TariffTimeline timeline;

    /// @param timeline the initial value of [#timeline()]; empty to start with no timeline
    public TariffTimelineNodeImpl(GraphRunner runner,
                                  AgreementWindowNode agreementWindowNode,
                                  TariffSourcesNode tariffSourcesNode,
                                  Optional<TariffTimeline> timeline) {
        super(runner);
        this.agreementWindowNode = subscribeTo(checkNotNull(agreementWindowNode));
        this.tariffSourcesNode = subscribeTo(checkNotNull(tariffSourcesNode));
        this.timeline = timeline.orElse(null);
    }

    @Override
    public @Nullable TariffTimeline timeline() {
        return timeline;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: timeline={}", when, timeline);
    }

    @Override
    protected boolean doWave() {
        List<Tariff> agreements = agreementWindowNode.agreements();
        Map<String, TariffResolution> resolutionsByTariffCode = tariffSourcesNode.resolutionsByTariffCode();
        if (agreements == null || resolutionsByTariffCode == null) {
            return false;
        }
        TariffTimeline newTimeline = createTimeline(agreements, resolutionsByTariffCode);
        if (newTimeline == null || newTimeline.equals(timeline)) {
            return false;
        }
        timeline = newTimeline;
        return true;
    }

    /// @return the timeline of `agreements`; `null` when the tariff of one of them is not resolved
    private static @Nullable TariffTimeline createTimeline(List<Tariff> agreements, Map<String, TariffResolution> resolutionsByTariffCode) {
        ImmutableList.Builder<TariffPeriod> periods = ImmutableList.builderWithExpectedSize(agreements.size());
        for (Tariff agreement : agreements) {
            if (!(resolutionsByTariffCode.get(agreement.tariffCode()) instanceof Resolved(TariffPriceSource source))) {
                return null;
            }
            periods.add(switch (source) {
                case TariffPriceSource.Priced(_, var pricing, var supplierManagedCharging) -> createPeriod(agreement, pricing, supplierManagedCharging);
                case TariffPriceSource.Unsupported _ -> createPeriod(agreement, TariffPricing.UNSUPPORTED, false);
            });
        }
        return new TariffTimeline(periods.build());
    }

    private static TariffPeriod createPeriod(Tariff agreement, TariffPricing pricing, boolean supplierManagedCharging) {
        return TariffPeriod.builder()
                           .setFrom(agreement.validFrom())
                           .setTo(agreement.validTo())
                           .setPricing(pricing)
                           .setSupplierManagedCharging(supplierManagedCharging)
                           .build();
    }
}
