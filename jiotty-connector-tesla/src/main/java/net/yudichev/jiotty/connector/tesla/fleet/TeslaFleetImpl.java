package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.net.SslCustomisation;
import net.yudichev.jiotty.common.rest.ContentTypes;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URLEncoder;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;


public final class TeslaFleetImpl extends BaseLifecycleComponent implements TeslaFleet {
    static final String AUDIENCE = "https://fleet-api.prd.eu.vn.cloud.tesla.com";
    private static final Logger logger = LogManager.getLogger(TeslaFleetImpl.class);
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final TypeToken<ResponseWrapper<List<TeslaVehicleData>>> LIST_VEHICLES_RESPONSE_TYPE = new TypeToken<>() {};
    private static final TypeToken<ResponseWrapper<CommandResponse>> CMD_RESPONSE_TYPE = new TypeToken<>() {};
    private final OAuth2TokenManager tokenManager;
    private final String baseUrl;
    private final AtomicInteger requestIdGenerator = new AtomicInteger();
    private final String listVehiclesUrl;
    private final String telemetryConfigCreateUrl;
    private final String registerPartnerDomainUrl;
    private final @Nullable SslCustomisation sslCustomisation;

    private OkHttpClient httpClient;
    @Nullable
    private Closeable tokenSubscription;
    private volatile String accessToken;

    @Inject
    public TeslaFleetImpl(@Dependency OAuth2TokenManager tokenManager,
                          @BaseUrl String baseUrl,
                          @Dependency Optional<SslCustomisation> sslCustomisation) {
        this.tokenManager = checkNotNull(tokenManager);
        this.baseUrl = Objects.requireNonNull(baseUrl);
        this.sslCustomisation = sslCustomisation.orElse(null);
        listVehiclesUrl = baseUrl + "/vehicles";
        telemetryConfigCreateUrl = listVehiclesUrl + "/fleet_telemetry_config";
        registerPartnerDomainUrl = baseUrl + "/partner_accounts";
    }

    @Override
    protected void doStart() {
        httpClient = newClient(builder -> {
            if (sslCustomisation != null) {
                builder.sslSocketFactory(sslCustomisation.socketFactory(), sslCustomisation.trustManager());
            }
        });
        CompletableFuture<Void> firstToken = new CompletableFuture<>();
        tokenSubscription = tokenManager.subscribeToAccessToken(token -> {
            boolean firstTime = accessToken == null;
            accessToken = token;
            if (firstTime) {
                firstToken.complete(null);
            }
        });
        if (!firstToken.isDone()) {
            logger.info("Awaiting for the access token to be delivered...");
            asUnchecked(() -> firstToken.get(5, TimeUnit.MINUTES));
            logger.info("Access token obtained");
        }
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, tokenSubscription, () -> shutdown(httpClient));
    }

    @Override
    public CompletableFuture<List<TeslaVehicleData>> listVehicles() {
        return whenStartedAndNotLifecycling(() -> {
            var request = new Request.Builder()
                    .url(listVehiclesUrl)
                    .header("Authorization", "Bearer " + accessToken)
                    .get()
                    .build();

            int requestId = requestIdGenerator.incrementAndGet();
            logger.debug("[{}] executing GET {}", requestId, listVehiclesUrl);
            // https://developer.tesla.com/docs/fleet-api/endpoints/vehicle-endpoints#list
            // Ignoring pagination
            return call(httpClient.newCall(request), LIST_VEHICLES_RESPONSE_TYPE, 0)
                    .whenComplete((resp, throwable) -> logger.debug("[{}] result {}", requestId, resp, throwable))
                    .thenApply(listResponseWrapper -> listResponseWrapper.response()
                                                                         .orElseThrow(() -> new IllegalStateException("No 'response' attribute in response")));
        });
    }

    @Override
    public CompletableFuture<PartnerAccount> registerPartnerDomain(String domain) {
        // first, get the partner token
        RequestBody form = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", tokenManager.clientId())
                .add("client_secret", tokenManager.clientSecret())
                .add("scope", tokenManager.scope())
                .add("audience", AUDIENCE)
                .build();

        Request request = new Request.Builder()
                .url("https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token")
                .post(form)
                .build();

        int requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] executing {}", requestId, request.url());
        var httpClient = newClient(); // use non-customised generic client
        return call(httpClient.newCall(request), TokenResponse.class, 0)
                .whenComplete((resp, throwable) -> {
                    logger.debug("[{}] result {}", requestId, resp, throwable);
                    shutdown(httpClient);
                })
                .thenCompose(tokenResponse ->
                                     // with the token, register the domain
                                     executePost(registerPartnerDomainUrl,
                                                 tokenResponse.accessToken(),
                                                 "{\"domain\": \"" + domain + "\"}",
                                                 new TypeToken<ResponseWrapper<PartnerAccount>>() {})
                                             .thenApply(unwrapOrFail()));
    }

    @Override
    public CompletableFuture<TelemetryCreateConfigResponse> telemetryCreateConfig(TelemetryCreateConfigRequest request) {
        return executePost(telemetryConfigCreateUrl, Json.stringify(request), new TypeToken<ResponseWrapper<TelemetryCreateConfigResponse>>() {})
                .thenApply(unwrapOrFail());
    }

    private static <T> Function<ResponseWrapper<T>, T> unwrapOrFail() {
        return responseWrapper -> responseWrapper.responseOrError()
                                                 .map(response -> response,
                                                      error -> {throw new RuntimeException(error);});
    }

    @Override
    public TeslaVehicle vehicle(String vin) {
        return new TeslaVehicleImpl(vin);
    }


    private <T> CompletableFuture<Optional<T>> executeGetForData(String url, TypeToken<ResponseWrapper<T>> responseType) {
        var request = new Request.Builder().url(url)
                                           .header("Authorization", "Bearer " + accessToken)
                                           .get()
                                           .build();
        int requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] executing {}", requestId, url);
        return callAndAllow408(requestId, httpClient.newCall(request), responseType)
                .whenComplete((resp, throwable) -> logger.debug("[{}] result {}", requestId, resp, throwable))
                .thenApply(wrapperOptional -> wrapperOptional.map(unwrapOrFail()));
    }

    private <T> CompletableFuture<T> executeGet(String url, TypeToken<ResponseWrapper<T>> responseType) {
        var request = new Request.Builder().url(url).header("Authorization", "Bearer " + accessToken).get().build();
        return call(httpClient.newCall(request), responseType, 0).thenApply(unwrapOrFail());
    }

    private static <T> CompletableFuture<Optional<T>> callAndAllow408(int requestId, Call theCall, TypeToken<? extends T> responseType) {
        CompletableFuture<Optional<T>> future = new CompletableFuture<>();
        theCall.enqueue(new Callback() {

            private static final int RESPONSE_CODE_OFFLINE = 408;

            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(new RuntimeException("call failed: " + call.request(), e));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody responseBody = response.body()) {
                    try {
                        String responseString = responseBody.string();
                        logger.debug("[{}] response: {}", requestId, responseString);
                        if (response.isSuccessful()) {
                            T responseData;
                            try {
                                responseData = Json.parse(responseString, responseType);
                                future.complete(Optional.of(responseData));
                            } catch (RuntimeException e) {
                                future.completeExceptionally(new RuntimeException("Failed parsing response " + responseString, e));
                            }
                        } else if (response.code() == RESPONSE_CODE_OFFLINE) {
                            future.complete(Optional.empty());
                        } else {
                            future.completeExceptionally(new RuntimeException(
                                    "Response code " + response.code() + ", body: " + safelyToString(responseBody)));
                        }
                    } catch (RuntimeException | IOException e) {
                        future.completeExceptionally(new RuntimeException("failed to process response body", e));
                    }
                }
            }

            private static String safelyToString(ResponseBody responseBody) {
                try {
                    return responseBody.string();
                } catch (@SuppressWarnings("OverlyBroadCatchBlock") Exception e) {
                    return "<failed to read body: " + humanReadableMessage(e) + ">";
                }
            }
        });
        return future;
    }

    private CompletableFuture<Optional<String>> postCommand(String url, @Nullable String jsonBody) {
        return executePost(url, jsonBody, CMD_RESPONSE_TYPE)
                .thenApply(responseWrapper ->
                                   responseWrapper.responseOrError().map(
                                           response -> {
                                               if (response.result()) {
                                                   return Optional.empty();
                                               } else {
                                                   throw new RuntimeException(response.reason().orElse("unknown reason"));
                                               }
                                           },
                                           Optional::of));
    }

    private <T> CompletableFuture<ResponseWrapper<T>> executePost(String url,
                                                                  @Nullable String jsonBody,
                                                                  TypeToken<ResponseWrapper<T>> responseType) {
        return executePost(url, accessToken, jsonBody, responseType);
    }

    private <T> CompletableFuture<ResponseWrapper<T>> executePost(String url,
                                                                  String accessToken,
                                                                  @Nullable String jsonBody,
                                                                  TypeToken<ResponseWrapper<T>> responseType) {
        Request.Builder builder = new Request.Builder().url(url)
                                                       .header("Authorization", "Bearer " + accessToken);
        if (jsonBody == null) {
            builder.post(RequestBody.create(EMPTY_BYTE_ARRAY, MediaType.get(ContentTypes.CONTENT_TYPE_JSON)));
        } else {
            builder.post(RequestBody.create(jsonBody, MediaType.get(ContentTypes.CONTENT_TYPE_JSON)));
        }
        Request request = builder.build();
        int requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] executing POST {} {}", requestId, url, jsonBody == null ? "" : jsonBody);
        return call(httpClient.newCall(request), responseType, 0, true)
                .whenComplete((resp, throwable) -> logger.debug("[{}] result {}", requestId, resp, throwable));
    }

    private <T> CompletableFuture<T> executeDelete(String url, TypeToken<ResponseWrapper<T>> responseType) {
        Request request = new Request.Builder().url(url)
                                               .header("Authorization", "Bearer " + accessToken)
                                               .delete()
                                               .build();
        int requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] executing DELETE {}", requestId, url);
        return call(httpClient.newCall(request), responseType, 0, true)
                .whenComplete((resp, throwable) -> logger.debug("[{}] result {}", requestId, resp, throwable))
                .thenApply(unwrapOrFail());
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface BaseUrl {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    private final class TeslaVehicleImpl implements TeslaVehicle {
        private static final TypeToken<ResponseWrapper<VehicleData>> GET_VEHICLE_DATA_RESPONSE_TYPE = new TypeToken<>() {};

        private final String vin;
        private final String getDataUrl;
        private final String telemetryConfigUrl;
        private final String telemetryFleetErrorsUrl;
        private final String telemetryFleetStatusUrl;
        private final String wakeUpUrl;
        private final String setChargeLimitUrl;
        private final String chargeStartUrl;
        private final String chargeStopUrl;
        private final String autoConditioningStartUrl;
        private final String autoConditioningStopUrl;

        TeslaVehicleImpl(String vin) {
            getDataUrl = baseUrl + "/vehicles/" + vin + "/vehicle_data";
            telemetryConfigUrl = baseUrl + "/vehicles/" + vin + "/fleet_telemetry_config";
            telemetryFleetErrorsUrl = baseUrl + "/vehicles/" + vin + "/fleet_telemetry_errors";
            telemetryFleetStatusUrl = baseUrl + "/vehicles/fleet_status";
            wakeUpUrl = baseUrl + "/vehicles/" + vin + "/wake_up";
            setChargeLimitUrl = baseUrl + "/vehicles/" + vin + "/command/set_charge_limit";
            chargeStartUrl = baseUrl + "/vehicles/" + vin + "/command/charge_start";
            chargeStopUrl = baseUrl + "/vehicles/" + vin + "/command/charge_stop";
            autoConditioningStartUrl = baseUrl + "/vehicles/" + vin + "/command/auto_conditioning_start";
            autoConditioningStopUrl = baseUrl + "/vehicles/" + vin + "/command/auto_conditioning_stop";
            this.vin = vin;
        }

        @Override
        public CompletableFuture<Optional<VehicleData>> getData(Set<Endpoint> endpoints) {
            /*
            String of URL-encoded, semicolon-separated values. Can be many of 'charge_state', 'climate_state', 'closures_state', 'drive_state',
             'gui_settings', 'location_data', 'charge_schedule_data', 'preconditioning_schedule_data', 'vehicle_config', 'vehicle_state',
              'vehicle_data_combo'. The 'location_data' and 'location_state' endpoints require 'vehicle_location' scope
             */
            // GET vehicle_data
            String url = getDataUrl + "?endpoints=" + URLEncoder.encode(endpoints.stream().map(Endpoint::id).collect(joining(";")), UTF_8);
            return executeGetForData(url, GET_VEHICLE_DATA_RESPONSE_TYPE);
        }

        @Override
        public String vin() {
            return vin;
        }

        @Override
        public CompletableFuture<Void> wakeUp() {
            return executePost(wakeUpUrl, null, new TypeToken<ResponseWrapper<WakeUpResult>>() {})
                    .thenApply(ignored -> null);
        }

        @Override
        public CompletableFuture<Optional<String>> setChargeLimit(int limitPercent) {
            String json = "{\"percent\":" + limitPercent + "}";
            return postCommand(setChargeLimitUrl, json);
        }

        @Override
        public CompletableFuture<Optional<String>> startCharge() {
            return postCommand(chargeStartUrl, null);
        }

        @Override
        public CompletableFuture<Optional<String>> stopCharge() {
            return postCommand(chargeStopUrl, null);
        }

        @Override
        public CompletableFuture<Optional<String>> startAutoConditioning() {
            return postCommand(autoConditioningStartUrl, null);
        }

        @Override
        public CompletableFuture<Optional<String>> stopAutoConditioning() {
            return postCommand(autoConditioningStopUrl, null);
        }

        @Override
        public CompletableFuture<TelemetryGetConfigResponse> telemetryGetConfig() {
            return executeGet(telemetryConfigUrl, new TypeToken<>() {});
        }

        @Override
        public CompletableFuture<TelemetryDeleteConfigResponse> telemetryDeleteConfig() {
            return executeDelete(telemetryConfigUrl, new TypeToken<>() {});
        }

        @Override
        public CompletableFuture<TelemetryFleetStatus> telemetryFleetStatus() {
            return executePost(telemetryFleetStatusUrl,
                               Json.stringify(TelemetryFleetStatusRequest.of(List.of(vin))),
                               new TypeToken<ResponseWrapper<TelemetryFleetStatus>>() {})
                    .thenApply(unwrapOrFail());
        }

        @Override
        public CompletableFuture<TelemetryFleetErrorsResponse> telemetryFleetErrors() {
            return executeGet(telemetryFleetErrorsUrl, new TypeToken<>() {});
        }
    }
}