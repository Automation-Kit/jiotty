package net.yudichev.jiotty.user.ui.options;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;

/// Option for editing a geographic location as a single `LatLon`.
///
/// On the wire, `onFormSubmit` accepts a JSON object `{"lat": <number>, "lon": <number>}` (or blank to clear). The DTO sent to the UI exposes `lat`/`lon` as
/// numeric fields directly.
public abstract class LocationOption extends BaseOption<LatLon> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /// The answer to a body that holds no location. It names no coordinate, so one instance serves every refusal.
    private static final FormSubmitResult INVALID_LOCATION = FormSubmitResult.rejected(OptionRejectionReasons.INVALID_LOCATION);

    protected LocationOption(TaskExecutor executor, OptionMeta<LatLon> meta) {
        super(executor, meta);
    }

    @Override
    public CompletableFuture<FormSubmitResult> onFormSubmit(Optional<String> value) {
        Optional<String> json = value.map(String::trim).filter(s -> !s.isEmpty());
        if (json.isEmpty()) {
            // Clearing the option saves null.
            return submit(null);
        }
        LatLon parsedLocation = parse(json.get());
        return parsedLocation == null ? completedFuture(INVALID_LOCATION) : submit(parsedLocation);
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

    /// The location `json` holds, or `null` when it holds none. Every way of failing is the same refusal, and none of them says which coordinate was wrong —
    /// that reaches the app and the log alike, and neither should hold someone's location.
    private static @Nullable LatLon parse(String json) {
        // Read through boxed fields: LatLon's are primitive, so a payload missing a coordinate, or sending it as null, would arrive as 0.0 and save as a
        // point off the coast of Africa.
        Payload payload;
        try {
            payload = MAPPER.readValue(json, Payload.class);
        } catch (JsonProcessingException e) {
            return null;
        }
        if (payload == null || payload.lat() == null || payload.lon() == null) {
            return null;
        }
        double lat = payload.lat();
        double lon = payload.lon();
        // Testing for "in range" and negating it also rejects NaN, which compares false against every bound and so would pass an "out of range" test.
        return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0 ? new LatLon(lat, lon) : null;
    }

    /// What arrives on the wire, before it is known to be a location.
    private record Payload(@Nullable Double lat, @Nullable Double lon) {}
}
