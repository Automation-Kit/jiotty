package net.yudichev.jiotty.user.ui.options;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.CompletableFutures;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;

/// Option for editing a geographic location as a single `LatLon`.
///
/// On the wire, `onFormSubmit` accepts a JSON object `{"lat": <number>, "lon": <number>}` (or blank to clear). The DTO sent to the UI exposes `lat`/`lon` as
/// numeric fields directly.
public abstract class LocationOption extends BaseOption<LatLon> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    protected LocationOption(TaskExecutor executor, OptionMeta<LatLon> meta) {
        super(executor, meta);
    }

    @Override
    public CompletableFuture<?> onFormSubmit(Optional<String> value) {
        LatLon parsed;
        try {
            parsed = value.map(String::trim)
                          .filter(s -> !s.isEmpty())
                          .map(LocationOption::parse)
                          .orElse(null);
        } catch (IllegalArgumentException e) {
            return CompletableFutures.failure(e);
        }
        return setValue(parsed);
    }

    @Override
    public OptionDto toDtoUnsafe() {
        return new StandardOptionDtos.Location("location",
                                               meta().key(),
                                               meta().label(),
                                               meta().tabName(),
                                               getFormOrder(),
                                               value());
    }

    private static LatLon parse(String json) {
        LatLon parsed;
        try {
            parsed = MAPPER.readValue(json, LatLon.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid location JSON: " + e.getMessage(), e);
        }
        checkArgument(parsed.lat() >= -90.0 && parsed.lat() <= 90.0, "Latitude out of range [-90, 90]: %s", parsed.lat());
        checkArgument(parsed.lon() >= -180.0 && parsed.lon() <= 180.0, "Longitude out of range [-180, 180]: %s", parsed.lon());
        return parsed;
    }
}
