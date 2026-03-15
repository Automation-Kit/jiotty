package net.yudichev.jiotty.connector.pushover;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

final class PushoverUserAlerter extends BaseLifecycleComponent implements UserAlerter {
    private static final Logger logger = LoggerFactory.getLogger(PushoverUserAlerter.class);

    private final String apiToken;
    private OkHttpClient httpClient;

    @Inject
    public PushoverUserAlerter(@ApiToken String apiToken) {
        this.apiToken = checkNotNull(apiToken);
    }

    @Override
    public void sendAlert(User user, MessagePriority priority, String text) {
        whenStartedAndNotLifecycling(() -> {
            var bodyBuilder = new FormBody.Builder()
                    .add("token", apiToken)
                    .add("user", user.token())
                    .add("message", text)
                    .add("sound", "updown")
                    .add("priority", String.valueOf(priority.priority()));
            if (priority == MessagePriority.EMERGENCY) {
                bodyBuilder.add("retry", "30")
                           .add("expire", String.valueOf(MINUTES.toSeconds(5)));
            }
            Request request = new Request.Builder()
                    .url("https://api.pushover.net/1/messages.json")
                    .post(bodyBuilder.build())
                    .build();
            call(httpClient.newCall(request), JsonNode.class)
                    .whenComplete(CompletableFutures.logErrorOnFailure(logger, "Failed sending alert"));
        });
    }

    @Override
    protected void doStart() {
        httpClient = newClient();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(httpClient));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ApiToken {
    }
}
