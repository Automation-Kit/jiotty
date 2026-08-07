package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.keystore.KeyStoreAccessModule;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.net.SslCustomisationModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.connector.mqtt.MqttModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.keystore.KeyStoreEntryModule.keyStoreEntry;
import static net.yudichev.jiotty.common.net.SslCustomisation.TrustStore;

final class MqttTeslaTelemetryManualRunner {

    private static final Logger log = LogManager.getLogger(MqttTeslaTelemetryManualRunner.class);
    private static String vin;

    static void main(String[] args) {
        vin = args[0];
        String mqttServerUri = args[1];
        String pathToKeyStore = args[2];
        String keyStorePass = args[3];
        Path trustStorePath = Paths.get(args[4]);
        String keyOfTrustStorePassInKeyStore = args[5];
        Path clientKeyStorePath = Paths.get(args[6]);
        String keyOfClientKeyStorePassInKeyStore = args[7];
        String mqttTopicBase = args[8];
        Application.builder()
                   .addModule(() -> TimeModule.builder().build())
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> KeyStoreAccessModule.builder()
                                                        .setPathToKeystore(literally(pathToKeyStore).map(new TypeToken<>() {},
                                                                                                         new TypeToken<>() {},
                                                                                                         Paths::get))
                                                        .setKeystorePass(literally(keyStorePass))
                                                        .build())
                   .addModule(() -> MqttTeslaTelemetryModule
                           .builder()
                           .withMqtt(exposedBy(
                                   MqttModule.builder()
                                             .setClientId(literally(MqttTeslaTelemetryManualRunner.class.getSimpleName()))
                                             .setServerUri(literally(mqttServerUri))
                                             .withConnectionOptionsCustomised(
                                                     exposedBy(SslCustomisationModule.builder()
                                                                                     .setCertTrustStore(
                                                                                             keyStoreEntry(keyOfTrustStorePassInKeyStore)
                                                                                                     .map(new TypeToken<>() {},
                                                                                                          new TypeToken<>() {},
                                                                                                          password -> new TrustStore(trustStorePath, password)))
                                                                                     .withClientKeyStore(keyStoreEntry(keyOfClientKeyStorePassInKeyStore)
                                                                                                                 .map(new TypeToken<>() {},
                                                                                                                      new TypeToken<>() {},
                                                                                                                      password -> new TrustStore(
                                                                                                                              clientKeyStorePath,
                                                                                                                              password)))
                                                                                     .build())
                                                             .map(new TypeToken<>() {},
                                                                  new TypeToken<>() {},
                                                                  jiottyTrustStoreSsl -> options -> {
                                                                      options.setCleanSession(false);
                                                                      options.setSocketFactory(jiottyTrustStoreSsl.socketFactory());
                                                                  }))
                                             .build()))
                           .withTopicBase(literally(mqttTopicBase))
                           .build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(CmdLineTest.class);
                       }
                   })
                   .build()
                   .run();
    }

    private static class CmdLineTest extends BaseLifecycleComponent {
        private final TeslaTelemetryFactory teslaTelemetryFactory;
        private Closeable subs;

        @Inject
        public CmdLineTest(TeslaTelemetryFactory teslaTelemetryFactory) {
            this.teslaTelemetryFactory = checkNotNull(teslaTelemetryFactory);
        }

        @Override
        protected void doStart() {
            var teslaTelemetry = teslaTelemetryFactory.create(vin);
            subs = Closeable.forCloseables(
                    teslaTelemetry.subscribeToConnectivity(result -> {
                        switch (result) {
                            case TelemetryResult.Success<TelemetryConnectivityEvent>(var event) -> log.info("CONNECTIVITY: {}", event);
                            case TelemetryResult.Error<TelemetryConnectivityEvent>(var message, var cause) ->
                                    log.warn("CONNECTIVITY ERROR: {}", message, cause);
                        }
                    }),
                    teslaTelemetry.subscribeToMetrics(result -> {
                        switch (result) {
                            case TelemetryResult.Success<TelemetryField>(var field) -> log.info("METRICS: {}={}", field.getClass().getSimpleName(), field);
                            case TelemetryResult.Error<TelemetryField>(var message, var cause) -> log.warn("METRICS ERROR: {}", message, cause);
                        }
                    }));
        }

        @Override
        protected void doStop() {
            subs.close();
        }
    }
}