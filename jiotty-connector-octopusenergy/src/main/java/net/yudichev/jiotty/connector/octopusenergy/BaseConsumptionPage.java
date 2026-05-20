package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PackagePrivateImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.util.List;
import java.util.Optional;

/// One page of a paginated `/electricity-meter-points/{mpan}/meters/{serial}/consumption/` response. Package-private wrapper — callers see only the assembled
/// [List] of [ConsumptionRow] from [OctopusAccountService#getConsumption].
@Immutable
@PackagePrivateImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseConsumptionPage {
    @JsonProperty("next")
    Optional<String> nextUrl();

    @JsonProperty("results")
    List<ConsumptionRow> results();
}
