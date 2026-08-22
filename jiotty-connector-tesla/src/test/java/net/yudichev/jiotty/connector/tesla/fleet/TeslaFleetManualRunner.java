package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.google.inject.AbstractModule;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.app.EnvProperties;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.GuiceUtil;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.net.SslCustomisationModule;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.connector.mqtt.Mqtt;
import net.yudichev.jiotty.connector.mqtt.MqttModule;
import net.yudichev.jiotty.persistence.db.DbConnectionConfig;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.process.InitModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static java.util.Map.ofEntries;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.keystore.KeyStoreEntryModule.keyStoreEntry;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.net.SslCustomisation.TrustStore;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevel;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TChargeLimitSoc;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDetailedChargeState;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDriveRail;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TGear;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacLeftTemperatureRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacPower;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacRightTemperatureRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TInsideTemp;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocation;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TOdometer;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TVehicleSpeed;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaVehicle.Endpoint.CHARGE_STATE;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaVehicle.Endpoint.CLIMATE_STATE;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaVehicle.Endpoint.LOCATION_DATA;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaVehicle.Endpoint.VEHICLE_STATE;

@SuppressWarnings("StaticVariableMayNotBeInitialized")
final class TeslaFleetManualRunner {

    private static final Logger logger = LogManager.getLogger(TeslaFleetManualRunner.class);

    /// Alias under which every environment's truststore holds the certificate whose public key signs the telemetry CA envelope.
    private static final String MQTT_CA_ALIAS = "mqtt-ca";

    private static String vin;
    private static String userId;
    private static EnvProperties env;

    static void main(String[] args) {
        env = EnvProperties.load(Paths.get(args[0]));
        vin = checkNotNull(args[1]);
        userId = checkNotNull(args[2]);
        Application.builder()
                   .addModule(() -> InitModule
                           .builder()
                           .setPathToKeystore(literally(Paths.get(env.require("KEYSTORE_PATH"))))
                           .setKeystorePass(literally(env.require("KEYSTORE_PASS")))
                           .setDbConnectionConfig(DbConnectionConfig.builder()
                                                                    .setHost(env.require("DB_HOST"))
                                                                    .setDbName(env.require("DB_NAME"))
                                                                    .setUsername(env.require("DB_USERNAME"))
                                                                    .setPort(5432)
                                                                    .setPasswordSpec(literally(env.require("DB_PASSWORD")))
                                                                    .build())
                           .setAppModuleFactory(_ -> new AbstractModule() {
                               @Override
                               protected void configure() {

                                   var clientIdSpec = keyStoreEntry("teslafleet-client-id");
                                   var clientSecretSpec = keyStoreEntry("teslafleet-client-secret");
                                   var vehicleCommandBaseUrl = literally(env.require("VEHICLE_COMMAND_BASE_URL"));
                                   var sslCustomisation = exposedBy(SslCustomisationModule
                                                                            .builder()
                                                                            .setCertTrustStore(createCertTrustStoreSpec(env))
                                                                            .build());
                                   install(TeslaFleetModule
                                                   .builder()
                                                   .setClientId(clientIdSpec)
                                                   .setClientSecret(clientSecretSpec)
                                                   .withOauthScopes(literally(ImmutableSet.of("openid",
                                                                                              "offline_access",
                                                                                              "vehicle_device_data",
                                                                                              "vehicle_location",
                                                                                              "vehicle_cmds",
                                                                                              "vehicle_charging_cmds")))
                                                   .withBaseUrl(vehicleCommandBaseUrl)
                                                   .withSslCustomisation(sslCustomisation)
                                                   .withVarStore(createUserVarStoreSpec(userId))
                                                   .build());
                                   install(TeslaFleetPartnerModule
                                                   .builder()
                                                   .setClientId(clientIdSpec)
                                                   .setClientSecret(clientSecretSpec)
                                                   .withScope(literally(String.join(" ", "openid",
                                                                                    "offline_access",
                                                                                    "vehicle_device_data",
                                                                                    "vehicle_location",
                                                                                    "vehicle_cmds",
                                                                                    "vehicle_charging_cmds")))
                                                   .withBaseUrl(vehicleCommandBaseUrl)
                                                   .withSslCustomisation(sslCustomisation)
                                                   .build());
                                   // Installed ahead of the component that subscribes to it: Mqtt.subscribe requires a started MqttImpl, and lifecycle
                                   // components start in binding-registration order.
                                   install(createTelemetryMqttModule(env));
                                   install(new BaseLifecycleComponentModule() {
                                       @Override
                                       protected void configure() {
                                           createMqttCaSigningKeySpec(env).bind(PublicKey.class)
                                                                          .annotatedWith(CmdLineTest.MqttCaSigningKey.class)
                                                                          .installedBy(this::installLifecycleComponentModule);
                                           registerLifecycleComponent(CmdLineTest.class);
                                       }
                                   });
                               }
                           })
                           .withVarStoreEncryptionKeyAlias(literally("varstore-master-key"))
                           .build())
                   .withAnnotation(SpecifiedAnnotation.forAnnotation(GuiceUtil.uniqueAnnotation()))
                   .build()
                   .run();
    }

    /// car-server keeps each user's Tesla refresh token in the varstore rows scoped to that user through [VarStore#forUser]. Reading the same scope lets this
    /// runner reuse that authorisation, since the OAuth redirect exchange that mints it belongs to car-server.
    private static BindingSpec<VarStore> createUserVarStoreSpec(String userId) {
        return BindingSpec.boundTo(VarStore.class)
                          .map(new TypeToken<>() {},
                               new TypeToken<>() {},
                               varStore -> varStore.forUser(userId));
    }

    private static BindingSpec<TrustStore> createCertTrustStoreSpec(EnvProperties env) {

        return keyStoreEntry(env.require("TRUSTSTORE_PASSWORD_KEYSTORE_ALIAS"))
                .map(new TypeToken<>() {},
                     new TypeToken<>() {},
                     password -> new TrustStore(Paths.get(env.require("TRUSTSTORE_PATH")), password));
    }

    /// mTLS with the same client keystore, so the broker resolves the same ACL identity and lets this runner
    /// read the CA envelope topic. The client id differs from car-server's so a local car-server and this runner can be connected at the same time.
    private static ExposedKeyModule<Mqtt> createTelemetryMqttModule(EnvProperties env) {
        var clientKeyStorePath = Paths.get(env.require("MQTT_CLIENT_KEYSTORE_PATH"));
        return MqttModule
                .builder()
                .setClientId(literally(TeslaFleetManualRunner.class.getSimpleName()))
                .setServerUri(literally(env.require("MQTT_BROKER_URL")))
                .withConnectionOptionsCustomised(
                        exposedBy(SslCustomisationModule
                                          .builder()
                                          .setCertTrustStore(createCertTrustStoreSpec(env))
                                          .withClientKeyStore(keyStoreEntry(env.require("MQTT_CLIENT_KEYSTORE_PASSWORD_KEYSTORE_ALIAS"))
                                                                      .map(new TypeToken<>() {},
                                                                           new TypeToken<>() {},
                                                                           password -> new TrustStore(clientKeyStorePath, password)))
                                          .build())
                                .map(new TypeToken<>() {},
                                     new TypeToken<>() {},
                                     ssl -> options -> options.setSocketFactory(ssl.socketFactory())))
                .build();
    }

    private static BindingSpec<PublicKey> createMqttCaSigningKeySpec(EnvProperties env) {
        var trustStorePath = Paths.get(env.require("TRUSTSTORE_PATH"));
        return keyStoreEntry(env.require("TRUSTSTORE_PASSWORD_KEYSTORE_ALIAS"))
                .map(new TypeToken<>() {},
                     new TypeToken<>() {},
                     password -> loadMqttCaPublicKey(trustStorePath, password));
    }

    private static PublicKey loadMqttCaPublicKey(Path trustStorePath, String trustStorePassword) {
        return getAsUnchecked(() -> {
            var keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream inputStream = Files.newInputStream(trustStorePath)) {
                keyStore.load(inputStream, trustStorePassword.toCharArray());
            }
            Certificate certificate = keyStore.getCertificate(MQTT_CA_ALIAS);
            checkState(certificate != null, "alias %s is absent from truststore %s", MQTT_CA_ALIAS, trustStorePath);
            return certificate.getPublicKey();
        });
    }

    @SuppressWarnings({"UseOfSystemOutOrSystemErr", "OverlyBroadCatchBlock", "CallToPrintStackTrace", "OverlyLongMethod",
            "DynamicRegexReplaceableByCompiledPattern"})
    private static class CmdLineTest extends BaseLifecycleComponent {
        private final TeslaFleet teslaFleet;
        private final TeslaFleetPartner teslaFleetPartner;
        private final Mqtt mqtt;
        private final PublicKey mqttCaSigningKey;
        /// Completed by the first telemetry CA envelope that verifies, and read by the `tlmcfgset` command. The envelope is retained on the broker, so it
        /// normally arrives within milliseconds of subscribing, and the first one that verifies settles the value for the whole run.
        private final CompletableFuture<String> caCertificateFuture = new CompletableFuture<>();
        private Closeable caCertSubscription;
        private Closeable tokenStateSubscription;
        private boolean listVehiclesInvoked;

        @Inject
        public CmdLineTest(TeslaFleet teslaFleet, TeslaFleetPartner teslaFleetPartner, Mqtt mqtt, @MqttCaSigningKey PublicKey mqttCaSigningKey) {
            this.teslaFleet = checkNotNull(teslaFleet);
            this.teslaFleetPartner = checkNotNull(teslaFleetPartner);
            this.mqtt = checkNotNull(mqtt);
            this.mqttCaSigningKey = checkNotNull(mqttCaSigningKey);
        }

        @Override
        protected void doStart() {
            caCertSubscription = mqtt.subscribe("tesla/telemetry_server_ca/signed", (_, envelope) -> onCaEnvelope(envelope));
            tokenStateSubscription = teslaFleet.subscribeToTokenState(this::onTokenState);
        }

        @Override
        protected void doStop() {
            closeSafelyIfNotNull(logger, tokenStateSubscription, caCertSubscription);
        }

        /// Decodes the envelope car-engine also consumes: a base64 `SHA256withRSA` signature of the payload bytes, a newline, then the PEM CA payload. The
        /// signature is verified against the truststore's `mqtt-ca` key, an integrity layer independent of the broker's ACL.
        private void onCaEnvelope(String envelope) {
            if (caCertificateFuture.isDone()) {
                return;
            }
            int separatorIndex = envelope.indexOf('\n');
            if (separatorIndex <= 0 || separatorIndex == envelope.length() - 1) {
                caCertificateFuture.completeExceptionally(
                        new IllegalStateException("Telemetry CA envelope is malformed: expected a base64 signature line followed by the CA payload"));
                return;
            }
            String signatureBase64 = envelope.substring(0, separatorIndex);
            if (signatureBase64.endsWith("\r")) {
                signatureBase64 = signatureBase64.substring(0, signatureBase64.length() - 1);
            }
            String payload = envelope.substring(separatorIndex + 1);
            if (verifyCaSignature(payload, signatureBase64)) {
                logger.info("Telemetry CA cert received and verified");
                caCertificateFuture.complete(payload);
            } else {
                caCertificateFuture.completeExceptionally(new IllegalStateException("Telemetry CA payload signature INVALID"));
            }
        }

        private boolean verifyCaSignature(String payload, String signatureBase64) {
            return getAsUnchecked(() -> {
                var verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(mqttCaSigningKey);
                verifier.update(payload.getBytes(UTF_8));
                return verifier.verify(Base64.getDecoder().decode(signatureBase64));
            });
        }

        /// The vehicle list waits for the first [AuthState.Success] because every Tesla API call needs a valid token. The state also arrives on subscribe, so
        /// a token the manager has already loaded proceeds immediately.
        private void onTokenState(AuthState authState) {
            switch (authState) {
                case AuthState.Success _ -> {
                    if (!listVehiclesInvoked) {
                        listVehiclesInvoked = true;
                        listVehicles();
                    }
                }
                // A permanent failure is terminal here: recovering needs the OAuth redirect exchange, which belongs to car-server. The message carries the
                // scope that was searched, so the operator can compare it against the scope that owns the token.
                case AuthState.PermanentFailure failure -> System.err.println(
                        "No usable Tesla token in the varstore scope of user " + userId + " (" + failure.description()
                        + "). Authorise that user in car-server, or re-run with the user id that owns the token: "
                        + "SELECT user_id FROM var_store WHERE key LIKE '%Oauth2Token%';");
                case AuthState.TransientFailure failure -> logger.info("Awaiting a valid Tesla token, current state is: {}", failure.description());
            }
        }

        private void listVehicles() {
            teslaFleet.listVehicles().whenComplete((teslaVehicles, throwable) -> {
                if (throwable != null) {
                    throwable.printStackTrace();
                } else {
                    processVehicles(teslaVehicles);
                }
            });
        }

        private void processVehicles(List<TeslaVehicleData> teslaVehicles) {
            System.out.println();
            for (TeslaVehicleData teslaVehicle : teslaVehicles) {
                System.out.println(teslaVehicle);
            }
            TeslaVehicleData vehicle = teslaVehicles.stream().filter(teslaVehicle -> teslaVehicle.vin().equals(vin)).findFirst().orElseThrow();
            TeslaVehicle car = teslaFleet.vehicle(vehicle.vin());
            new Thread(() -> {
                System.out.println("""
                                   ENTER COMMAND (regacct <domain>, get, wake, setlim <lim>, chstart, chstop, constart, constop, tlmcfgget, tlmcfgdel, \
                                   tlmcfgset, tlmfleet, tlmerrors):""");
                var reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = getAsUnchecked(reader::readLine)) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        try {
                            String[] cmd = line.split("\\s+");
                            var future = switch (cmd[0]) {
                                case "regacct" -> teslaFleetPartner.registerPartnerDomain(cmd[1]);
                                case "setlim" -> car.setChargeLimit(Integer.parseInt(cmd[1]));
                                case "chstart" -> car.startCharge();
                                case "chstop" -> car.stopCharge();
                                case "constart" -> car.startAutoConditioning();
                                case "constop" -> car.stopAutoConditioning();
                                case "wake" -> car.wakeUp();
                                case "get" -> car.getData(ImmutableSet.of(CHARGE_STATE, VEHICLE_STATE, LOCATION_DATA, CLIMATE_STATE));
                                case "tlmcfgget" -> car.telemetryGetConfig();
                                case "tlmcfgdel" -> car.telemetryDeleteConfig();
                                case "tlmcfgset" -> {
                                    var fieldParams = ofEntries(
                                            entry(TDetailedChargeState.NAME, TelemetryFieldParams.of(1)),
                                            entry(TBatteryLevel.NAME, TelemetryFieldParams.builder().setIntervalSeconds(30).setMinimumDelta(0.5).build()),
                                            entry(TChargeLimitSoc.NAME, TelemetryFieldParams.of(1)),
                                            entry(TLocation.NAME, TelemetryFieldParams.of(30)),
                                            entry(THvacPower.NAME, TelemetryFieldParams.of(1)),
                                            entry(TInsideTemp.NAME, TelemetryFieldParams.builder().setIntervalSeconds(30).setMinimumDelta(0.5).build()),
                                            entry(THvacLeftTemperatureRequest.NAME, TelemetryFieldParams.of(1)),
                                            entry(THvacRightTemperatureRequest.NAME, TelemetryFieldParams.of(1)),
                                            entry(TVehicleSpeed.NAME, TelemetryFieldParams.builder().setIntervalSeconds(30).setMinimumDelta(5.0).build()),
                                            entry(TOdometer.NAME, TelemetryFieldParams.of(60)),
                                            entry(TGear.NAME, TelemetryFieldParams.of(1)),
                                            entry(TDriveRail.NAME, TelemetryFieldParams.of(1)));
                                    yield teslaFleet.telemetryCreateConfig(
                                            TelemetryCreateConfigRequest
                                                    .builder()
                                                    .addVins(vehicle.vin())
                                                    .setConfig(TelemetryConfig.builder()
                                                                              .setHostname(env.require("TESLA_TELEMETRY_DOMAIN"))
                                                                              .setPort(443)
                                                                              .setCaCertificate(caCertificateFuture.get(20, SECONDS))
                                                                              .setFieldParams(fieldParams)
                                                                              .build())
                                                    .build());
                                }
                                case "tlmfleet" -> car.telemetryFleetStatus();
                                case "tlmerrors" -> car.telemetryFleetErrors();
                                default -> {
                                    System.err.println("What is this - " + Arrays.toString(cmd));
                                    yield CompletableFuture.completedFuture(null);
                                }
                            };
                            Object result = future.get(20, SECONDS);
                            System.out.println(result == null ? "Done" : "Result: " + result);
                        } catch (Exception e) {
                            System.out.println("Failure: " + humanReadableMessage(e));
                        }
                    }
                }
            }).start();
        }

        @BindingAnnotation
        @Target({FIELD, PARAMETER, METHOD})
        @Retention(RUNTIME)
        @interface MqttCaSigningKey {
        }
    }
}
