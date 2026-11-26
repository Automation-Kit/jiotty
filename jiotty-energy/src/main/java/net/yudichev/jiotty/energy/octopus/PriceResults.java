package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceResult;
import org.jspecify.annotations.Nullable;

import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;

/// A utility for comparing [PriceResult]s.
public final class PriceResults {
    private PriceResults() {
    }

    /// @param currentResult the published result, `null` before the first one
    /// @return whether `result` equals `currentResult`, or both are [Failure.PriceRetrievalError]s whose causes have the same
    ///         [HumanReadableExceptionMessage#humanReadableMessage(Throwable)]. Two such errors are not equal otherwise, since their causes compare by
    ///         identity.
    public static boolean isUnchanged(PriceResult result, @Nullable PriceResult currentResult) {
        return result.equals(currentResult)
               || result instanceof Failure.PriceRetrievalError(Throwable cause)
                  && currentResult instanceof Failure.PriceRetrievalError(Throwable currentCause)
                  && humanReadableMessage(cause).equals(humanReadableMessage(currentCause));
    }
}
