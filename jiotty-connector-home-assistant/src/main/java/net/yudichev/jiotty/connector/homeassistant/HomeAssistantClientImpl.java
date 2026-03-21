package net.yudichev.jiotty.connector.homeassistant;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.rest.ContentTypes;
import net.yudichev.jiotty.common.rest.RestClients;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

public final class HomeAssistantClientImpl extends BaseLifecycleComponent implements HomeAssistantClient {
    private static final Logger logger = LogManager.getLogger(HomeAssistantClientImpl.class);

    private final String baseUrl;
    private final String accessToken;
    private final Climate climate = new ClimateImpl();
    private final Switch theSwitch = new SwitchImpl();
    private final Number number = new NumberImpl();
    private final Button button = new ButtonImpl();
    private final Domain<Void> sensor = new BaseDomain<>("sensor") {};
    private final LogBook logBook = new LogBookImpl();
    private final History history = new HistoryImpl();
    private final BinarySensor binarySensor = new BinarySensorImpl();
    private final Domain<HADeviceLocationAttributes> deviceTracker = new BaseDomain<>("device_tracker") {};

    private final AtomicLong requestIdGenerator = new AtomicLong();

    private OkHttpClient client;

    @Inject
    public HomeAssistantClientImpl(@BaseUrl String baseUrl, @AccessToken String accessToken) {
        this.baseUrl = checkNotNull(baseUrl);
        this.accessToken = checkNotNull(accessToken);
    }

    @Override
    protected void doStart() {
        client = RestClients.newClient();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> RestClients.shutdown(client));
    }

    @Override
    public Climate climate() {
        return climate;
    }

    @Override
    public Switch aSwitch() {
        return theSwitch;
    }

    @Override
    public Number number() {
        return number;
    }

    @Override
    public Button button() {
        return button;
    }

    @Override
    public Domain<Void> sensor() {
        return sensor;
    }

    @Override
    public LogBook logBook() {
        return logBook;
    }

    @Override
    public History history() {
        return history;
    }

    @Override
    public BinarySensor binarySensor() {
        return binarySensor;
    }

    @Override
    public Domain<HADeviceLocationAttributes> deviceTracker() {
        return deviceTracker;
    }

    private <T> CompletableFuture<T> invokeGet(String path, TypeToken<T> responseType) {
        long requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] Invoking GET {}", requestId, path);
        var request = new Request.Builder().url(baseUrl + '/' + path)
                                           .header("Authorization", "Bearer " + accessToken)
                                           .get()
                                           .build();
        return callAndLogResponse(request, requestId, responseType);
    }

    private <T> CompletableFuture<T> callAndLogResponse(Request request, long requestId, TypeToken<T> responseType) {
        return whenStartedAndNotLifecycling(
                () -> RestClients.call(client.newCall(request), responseType)
                                 .whenComplete((o, throwable) -> logger.debug("[{}] Response {}", requestId, o, throwable)));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface BaseUrl {

    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccessToken {

    }

    private class BaseDomain<A> implements Domain<A> {
        protected final String domainId;
        private final TypeToken<A> attrType;

        protected BaseDomain(String domainId) {
            this.domainId = checkNotNull(domainId);
            attrType = new TypeToken<>(getClass()) {};
        }

        @Override
        public CompletableFuture<HAState<A>> getState(String domainlessEntityId) {
            return invokeGetState(domainlessEntityId);
        }

        protected CompletableFuture<List<HAState<A>>> invokePostServices(String service, Object body) {
            long requestId = requestIdGenerator.incrementAndGet();
            logger.debug("[{}] Invoking POST {}.{}({})", requestId, domainId, service, body);
            var request = new Request.Builder().url(baseUrl + "/services/" + domainId + "/" + service)
                                               .header("Authorization", "Bearer " + accessToken)
                                               .addHeader(CONTENT_TYPE, ContentTypes.CONTENT_TYPE_JSON)
                                               .post(RequestBody.create(Json.stringify(body), MediaType.get(ContentTypes.CONTENT_TYPE_JSON)))
                                               .build();
            return callAndLogResponse(request, requestId,
                                      new TypeToken<List<HAState<A>>>() {}
                                              .where(new TypeParameter<>() {}, attrType));
        }

        protected CompletableFuture<HAState<A>> invokeGetState(String domainlessEntityId) {
            return invokeGet("states/" + domainId + '.' + domainlessEntityId,
                             new TypeToken<HAState<A>>() {}
                                     .where(new TypeParameter<>() {}, attrType));
        }
    }

    private class ClimateImpl extends BaseDomain<HAClimateAttributes> implements Climate {
        public ClimateImpl() {
            super("climate");
        }

        @Override
        public CompletableFuture<List<HAState<HAClimateAttributes>>> setTemperature(String domainlessEntityId, String hvacMode, double temperature) {
            return invokePostServices("set_temperature", HAClimateSetTemperatureServiceData.builder()
                                                                                           .setEntityId(domainId + '.' + domainlessEntityId)
                                                                                           .setHvacMode(hvacMode)
                                                                                           .setTemperature(temperature)
                                                                                           .build());
        }

        @Override
        public CompletableFuture<List<HAState<HAClimateAttributes>>> setHvacMode(String domainlessEntityId, String hvacMode) {
            return invokePostServices("set_hvac_mode", HAClimateSetHvacModeServiceData.builder()
                                                                                      .setEntityId(domainId + '.' + domainlessEntityId)
                                                                                      .setHvacMode(hvacMode)
                                                                                      .build());
        }

        @Override
        public CompletableFuture<List<HAState<HAClimateAttributes>>> turnOn(String domainlessEntityId) {
            return invokePostServices("turn_on", HAServiceData.of(domainId + '.' + domainlessEntityId));
        }

        @Override
        public CompletableFuture<List<HAState<HAClimateAttributes>>> turnOff(String domainlessEntityId) {
            return invokePostServices("turn_off", HAServiceData.of(domainId + '.' + domainlessEntityId));
        }
    }

    private class SwitchImpl extends BaseDomain<Void> implements Switch {
        public SwitchImpl() {
            super("switch");
        }

        @Override
        public CompletableFuture<List<HAState<Void>>> turnOn(String domainlessEntityId) {
            return invokePostServices("turn_on", HAServiceData.of(domainId + '.' + domainlessEntityId));
        }

        @Override
        public CompletableFuture<List<HAState<Void>>> turnOff(String domainlessEntityId) {
            return invokePostServices("turn_off", HAServiceData.of(domainId + '.' + domainlessEntityId));
        }
    }

    private class NumberImpl extends BaseDomain<Void> implements Number {
        public NumberImpl() {
            super("number");
        }

        @Override
        public CompletableFuture<List<HAState<Void>>> setValue(String domainlessEntityId, double value) {
            return invokePostServices("set_value", HANumberSetValueServiceData.of(domainId + '.' + domainlessEntityId, value));
        }
    }

    private class ButtonImpl extends BaseDomain<Void> implements Button {
        public ButtonImpl() {
            super("button");
        }

        @Override
        public CompletableFuture<List<HAState<Void>>> press(String domainlessEntityId) {
            return invokePostServices("press", HAServiceData.of(domainId + '.' + domainlessEntityId));
        }
    }

    private class BinarySensorImpl extends BaseDomain<Void> implements BinarySensor {
        public BinarySensorImpl() {
            super("binary_sensor");
        }
    }

    private class LogBookImpl implements LogBook {

        @Override
        public CompletableFuture<List<HALogbookEntry>> get(String entityId, Optional<Instant> from, Optional<Instant> to) {
            return invokeGet("logbook" + from.map(t -> "/" + t).orElse("")
                             + "?entity=" + URLEncoder.encode(entityId, UTF_8)
                             + to.map(t -> "&end_time=" + t).orElse(""),
                             new TypeToken<>() {});
        }
    }

    private class HistoryImpl implements History {

        @Override
        public CompletableFuture<Map<String, List<HAHistoryEntry>>> get(List<String> entityIds, Optional<Instant> from, Optional<Instant> to) {
            checkArgument(!entityIds.isEmpty(), "entityIds mus not be empty");
            return invokeGet("history/period" + from.map(t -> "/" + t).orElse("")
                             + "?filter_entity_id=" + URLEncoder.encode(String.join(",", entityIds), UTF_8)
                             + "&minimal_response&no_attributes"
                             + to.map(t -> "&end_time=" + t).orElse(""),
                             new TypeToken<List<List<HAHistoryEntry>>>() {})
                    .thenApply(lists -> {
                        var resultBuilder = ImmutableMap.<String, List<HAHistoryEntry>>builderWithExpectedSize(lists.size());
                        for (List<HAHistoryEntry> entityList : lists) {
                            String entityId = entityList.isEmpty()
                                              ? null
                                              : entityList.getFirst()
                                                          .entityId()
                                                          .orElseThrow(() -> new RuntimeException("Unexpectedly missing entityId on the first history element"));
                            if (entityId != null) {
                                resultBuilder.put(entityId, entityList);
                            }
                        }
                        return resultBuilder.build();
                    });
        }
    }
}
