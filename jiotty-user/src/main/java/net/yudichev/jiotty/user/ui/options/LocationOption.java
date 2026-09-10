package net.yudichev.jiotty.user.ui.options;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;

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
            return failure(e);
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
        // Read through boxed fields: LatLon's are primitive, so a payload missing a coordinate, or sending it as null, would arrive as 0.0 and save as a
        // point off the coast of Africa.
        Payload payload;
        try {
            payload = MAPPER.readValue(json, Payload.class);
        } catch (JsonProcessingException e) {
            throw OptionValueRejectedException.of(OptionRejectionReasons.INVALID_LOCATION, e);
        }
        if (payload == null || payload.lat() == null || payload.lon() == null) {
            // The message names no coordinate — it goes to the app and to the log, and neither should hold someone's location.
            throw OptionValueRejectedException.of(OptionRejectionReasons.INVALID_LOCATION);
        }
        double lat = payload.lat();
        double lon = payload.lon();
        // Testing for "in range" and negating it also rejects NaN, which compares false against every bound and so would pass an "out of range" test.
        if (!(lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0)) {
            throw OptionValueRejectedException.of(OptionRejectionReasons.INVALID_LOCATION);
        }
        return new LatLon(lat, lon);
    }

    /// What arrives on the wire, before it is known to be a location.
    private record Payload(@Nullable Double lat, @Nullable Double lon) {}
}
