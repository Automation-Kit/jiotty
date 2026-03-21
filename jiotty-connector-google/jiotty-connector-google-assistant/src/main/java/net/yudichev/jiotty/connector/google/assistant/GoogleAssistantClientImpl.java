package net.yudichev.jiotty.connector.google.assistant;

import com.google.assistant.embedded.v1alpha2.AssistConfig;
import com.google.assistant.embedded.v1alpha2.AssistRequest;
import com.google.assistant.embedded.v1alpha2.AssistResponse;
import com.google.assistant.embedded.v1alpha2.AudioOutConfig;
import com.google.assistant.embedded.v1alpha2.DeviceConfig;
import com.google.assistant.embedded.v1alpha2.DialogStateIn;
import com.google.assistant.embedded.v1alpha2.EmbeddedAssistantGrpc;
import com.google.auth.Credentials;
import com.google.inject.BindingAnnotation;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.connector.google.common.impl.Bindings.Authorization;

final class GoogleAssistantClientImpl extends BaseLifecycleComponent implements GoogleAssistantClient {
    private static final Logger logger = LogManager.getLogger(GoogleAssistantClientImpl.class);

    private static final String HOSTNAME = "embeddedassistant.googleapis.com";
    private final AudioOutConfig audioOutConfig;
    private final DialogStateIn dialogStateIn;
    private final DeviceConfig deviceConfig;
    private final Provider<GoogleAuthorization> googleAuthorizationProvider;

    private EmbeddedAssistantGrpc.EmbeddedAssistantStub stub;
    private ManagedChannel channel;

    @Inject
    GoogleAssistantClientImpl(@Dependency AudioOutConfig audioOutConfig,
                              @Dependency DialogStateIn dialogStateIn,
                              @Dependency DeviceConfig deviceConfig,
                              @Authorization Provider<GoogleAuthorization> googleAuthorizationProvider) {
        this.audioOutConfig = checkNotNull(audioOutConfig);
        this.dialogStateIn = checkNotNull(dialogStateIn);
        this.deviceConfig = checkNotNull(deviceConfig);
        this.googleAuthorizationProvider = checkNotNull(googleAuthorizationProvider);
    }

    @Override
    public CompletableFuture<byte[]> assist(String phrase) {
        return whenStartedAndNotLifecycling(() -> {
            CompletableFuture<byte[]> result = new CompletableFuture<>();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            StreamObserver<AssistRequest> requestStreamObserver = stub.assist(new StreamObserver<>() {
                @Override
                public void onNext(AssistResponse assistResponse) {
                    logger.debug("assist response onNext: {}", assistResponse);
                    if (assistResponse.hasAudioOut()) {
                        asUnchecked(() -> assistResponse.getAudioOut().writeTo(outputStream));
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    logger.debug("assist response onError", throwable);
                    result.completeExceptionally(throwable);
                }

                @Override
                public void onCompleted() {
                    logger.debug("assist response onCompleted");
                    result.complete(outputStream.toByteArray());
                }
            });

            AssistRequest assistRequest = AssistRequest.newBuilder()
                                                       .setConfig(AssistConfig.newBuilder()
                                                                              .setTextQuery(phrase)
                                                                              .setAudioOutConfig(audioOutConfig)
                                                                              .setDialogStateIn(dialogStateIn)
                                                                              .setDeviceConfig(deviceConfig)
                                                                              .build())
                                                       .build();

            logger.debug("Issuing assist request: {}", assistRequest);
            requestStreamObserver.onNext(assistRequest);

            return result;
        });
    }

    @Override
    protected void doStart() {
        Credentials credentials = googleAuthorizationProvider.get().getCredentials();

        channel = ManagedChannelBuilder.forTarget(HOSTNAME).build();
        stub = EmbeddedAssistantGrpc.newStub(channel)
                                    .withCallCredentials(MoreCallCredentials.from(credentials));
    }

    @Override
    protected void doStop() {
        if (channel != null) {
            boolean success = getAsUnchecked(() -> channel.shutdown().awaitTermination(5, TimeUnit.SECONDS));
            if (!success) {
                logger.warn("Were not able to shut down Assistant channel in 5 seconds");
            }
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
