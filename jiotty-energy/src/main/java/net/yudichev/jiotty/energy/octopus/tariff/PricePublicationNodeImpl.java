package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Failed;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Retrieved;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/// A [PricePublicationNode] that starts with a given [#publishedResult()] and with no deferred failure, and publishes or defers each failure against its
/// latest [#publishedResult()].
final class PricePublicationNodeImpl extends BaseServerNode implements PricePublicationNode {
    private final TariffRetrieval retrieval;
    private final DailyRetrievalNode dailyRetrievalNode;
    private final PriceRetrievalNode priceRetrievalNode;

    /// The [DailyRetrievalNode#nextDailyRetrieval()] of the last wave; `null` before the first wave.
    private @Nullable Instant nextDailyRetrieval;
    /// The [PriceRetrievalNode#outcome()] that this node last acted on; `null` until it has acted on one.
    private @Nullable RetrievalOutcome outcome;
    /// The failure to publish at the next daily retrieval; `null` when there is none.
    private @Nullable Throwable deferredFailure;
    /// `null` until there is a result for subscribers to hold.
    private @Nullable PriceResult publishedResult;

    /// @param publishedResult the initial value of [#publishedResult()]; empty to start with none
    public PricePublicationNodeImpl(GraphRunner runner,
                                    TariffRetrieval retrieval,
                                    DailyRetrievalNode dailyRetrievalNode,
                                    PriceRetrievalNode priceRetrievalNode,
                                    Optional<PriceResult> publishedResult) {
        super(runner);
        this.retrieval = checkNotNull(retrieval);
        this.dailyRetrievalNode = subscribeTo(checkNotNull(dailyRetrievalNode));
        this.priceRetrievalNode = subscribeTo(checkNotNull(priceRetrievalNode));
        this.publishedResult = publishedResult.orElse(null);
    }

    @Override
    public @Nullable PriceResult publishedResult() {
        return publishedResult;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: nextDailyRetrieval={}, outcome={}, deferredFailure={}, publishedResult={}",
                     when, nextDailyRetrieval, outcome, deferredFailure, publishedResult);
    }

    @Override
    protected boolean doWave() {
        Instant newNextDailyRetrieval = dailyRetrievalNode.nextDailyRetrieval();
        if (newNextDailyRetrieval == null) {
            return false;
        }
        PriceResult previousPublishedResult = publishedResult;
        if (nextDailyRetrieval != null && !nextDailyRetrieval.equals(newNextDailyRetrieval) && deferredFailure != null) {
            publishedResult = new Failure.PriceRetrievalError(deferredFailure);
            deferredFailure = null;
        }
        nextDailyRetrieval = newNextDailyRetrieval;
        RetrievalOutcome newOutcome = priceRetrievalNode.outcome();
        if (newOutcome != null && newOutcome != outcome) {
            outcome = newOutcome;
            switch (newOutcome) {
                case Retrieved(Prices prices, _) -> {
                    deferredFailure = null;
                    publishedResult = prices;
                }
                case Failed(Throwable cause, _) -> {
                    if (retrieval.publishesFailureAtOnce(publishedResult instanceof Prices)) {
                        publishedResult = new Failure.PriceRetrievalError(cause);
                    } else {
                        deferredFailure = cause;
                    }
                }
            }
        }
        return publishedResult != previousPublishedResult;
    }
}
