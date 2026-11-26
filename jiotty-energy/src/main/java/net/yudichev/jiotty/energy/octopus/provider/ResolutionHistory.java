package net.yudichev.jiotty.energy.octopus.provider;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/// The latest [FailedResolution] of each tariff whose resolution has failed, until a resolution of that tariff succeeds.
final class ResolutionHistory {
    private final Map<String, FailedResolution> failedResolutionsByTariffCode = new HashMap<>();

    public void recordFailure(String tariffCode, FailedResolution failedResolution) {
        failedResolutionsByTariffCode.put(tariffCode, failedResolution);
    }

    public void recordSuccess(String tariffCode) {
        failedResolutionsByTariffCode.remove(tariffCode);
    }

    /// @return the latest [FailedResolution] of `tariffCode`, unless a resolution has succeeded since
    public Optional<FailedResolution> failedResolution(String tariffCode) {
        return Optional.ofNullable(failedResolutionsByTariffCode.get(tariffCode));
    }

    /// @param at    when the resolution failed
    /// @param cause why the resolution failed
    public record FailedResolution(Instant at, Throwable cause) {
        public FailedResolution {
            checkNotNull(at);
            checkNotNull(cause);
        }
    }
}
