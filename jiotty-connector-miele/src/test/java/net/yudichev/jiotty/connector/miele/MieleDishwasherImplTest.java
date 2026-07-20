package net.yudichev.jiotty.connector.miele;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.google.common.base.Throwables;
import net.yudichev.jiotty.common.async.ExecutorFactoryImpl;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.TimeProvider;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;

/// Originally from <a href="https://chatgpt.com/c/6754cbf0-1374-800b-9e27-9ff9d91b33f0">here</a>
@WireMockTest
class MieleDishwasherImplTest {
    private static final Logger logger = LogManager.getLogger(MieleDishwasherImplTest.class);

    private static final String DEVICE_ID = "test-device-id";
    private static final String ACCESS_TOKEN = "mock-access-token";

    private MieleDishwasherImpl mieleDishwasher;
    private String baseUrl;
    /// The states the token manager delivers, in order, when the dishwasher subscribes on start. A real manager holds its latest state and hands it over on
    /// subscription, so a subscriber can receive a yet-to-be-resolved state ahead of the one that resolves it.
    private List<AuthState> authStatesOnSubscribe = List.of(new AuthState.Success(ACCESS_TOKEN));

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        baseUrl = wmRuntimeInfo.getHttpBaseUrl();
        mieleDishwasher = createDishwasher();
        mieleDishwasher.start();
    }

    private MieleDishwasherImpl createDishwasher() {
        OAuth2TokenManager mockTokenManager = new OAuth2TokenManager() {
            @Override
            public Optional<String> clientSecret() {
                return Optional.empty();
            }

            @Override
            public String clientId() {
                return "";
            }

            @Override
            public String scope() {
                return "";
            }

            @Override
            public Closeable subscribeToAccessTokenState(Consumer<? super AuthState> handler) {
                authStatesOnSubscribe.forEach(handler);
                return () -> {};
            }

            @Override
            public void invalidate(String rejectedAccessToken, String reason) {
            }

            @Override
            public void onNewAuthCode(String authCode, String redirectUri, Optional<String> codeVerifier) {
            }
        };

        return new MieleDishwasherImpl(
                DEVICE_ID,
                mockTokenManager,
                new ExecutorFactoryImpl(),
                new TimeProvider(),
                RetryableOperationExecutor.noRetries(),
                baseUrl,
                builder -> builder.connectTimeout(Duration.ofSeconds(1))
                                  .callTimeout(Duration.ofSeconds(1))
                                  .readTimeout(Duration.ofSeconds(1))
                                  .writeTimeout(Duration.ofSeconds(1)),
                _ -> {},
                1);
    }

    @AfterEach
    void tearDown() {
        Closeable.closeSafelyIfNotNull(logger, () -> mieleDishwasher.stop());
    }

    @Test
    void start_transientFailureThenToken_starts() {
        // The manager hands its current state to a new subscriber, and until it resolves a token that state is a transient failure; the token that follows
        // completes the start.
        mieleDishwasher.stop();
        authStatesOnSubscribe = List.of(new AuthState.TransientFailure("Initialising"), new AuthState.Success(ACCESS_TOKEN));
        mieleDishwasher = createDishwasher();

        mieleDishwasher.start();

        stubFor(get(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                        .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                        .willReturn(aResponse().withStatus(200)
                                               .withHeader("Content-Type", "application/json")
                                               .withBody("[]")));
        assertThat(mieleDishwasher.getPrograms().join()).isEmpty();
    }

    @Test
    void refreshedToken_isUsedForSubsequentRequests() {
        // The manager publishes a fresh token whenever it refreshes one, and every later request carries it.
        mieleDishwasher.stop();
        authStatesOnSubscribe = List.of(new AuthState.Success("first-token"), new AuthState.Success("refreshed-token"));
        mieleDishwasher = createDishwasher();

        mieleDishwasher.start();

        stubFor(get(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                        .withHeader("Authorization", equalTo("Bearer refreshed-token"))
                        .willReturn(aResponse().withStatus(200)
                                               .withHeader("Content-Type", "application/json")
                                               .withBody("[]")));
        assertThat(mieleDishwasher.getPrograms().join()).isEmpty();
    }

    @Test
    void start_permanentFailure_fails() {
        mieleDishwasher.stop();
        authStatesOnSubscribe = List.of(new AuthState.PermanentFailure("not authenticated"));
        mieleDishwasher = createDishwasher();

        assertThatThrownBy(() -> mieleDishwasher.start())
                .hasRootCauseMessage("Failed to obtain access token: not authenticated");
    }

    @Test
    void getPrograms_successfulResponse_returnsPrograms() {
        stubFor(get(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                        .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                        .willReturn(aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody("""
                                                      [
                                                          {"programId": 1, "program": "Program 1"},
                                                          {"programId": 2, "program": "Program 2"}
                                                      ]
                                                      """)));

        List<MieleProgram> programs = mieleDishwasher.getPrograms().join();

        assertThat(programs).hasSize(2)
                            .extracting("name")
                            .containsExactly("Program 1", "Program 2");
    }

    @Test
    void getPrograms_timeout_throwsException() {
        stubFor(get(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                        .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                        .willReturn(aResponse()
                                            .withFixedDelay(1500) // Delay longer than client timeout
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody("[]")));

        assertThatThrownBy(() -> mieleDishwasher.getPrograms().join())
                .extracting(Throwables::getCausalChain).asInstanceOf(list(Throwable.class))
                .anySatisfy(e -> assertThat(e).satisfiesAnyOf(
                        // different environments / okhttp versions produce different results here
                        ex -> assertThat(ex).isInstanceOf(SocketTimeoutException.class),
                        ex -> assertThat(ex).message().containsIgnoringCase("timeout")
                ));
    }

    @Test
    void getPrograms_serverError_throwsException() {
        stubFor(get(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                        .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                        .willReturn(aResponse()
                                            .withStatus(500)
                                            .withBody("Internal Server Error")));

        assertThatThrownBy(() -> mieleDishwasher.getPrograms().join())
                .hasMessageContaining("500");
    }

    @Test
    void startProgram_successful() {
        stubFor(put(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                        .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                        .withRequestBody(equalToJson("""
                                                     {"programId": 123}
                                                     """))
                        .willReturn(aResponse()
                                            .withStatus(204)));

        mieleDishwasher.startProgram(123).join();

        verify(putRequestedFor(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                       .withRequestBody(equalToJson("""
                                                    {"programId": 123}
                                                    """)));
    }

    @Test
    void startProgram_connectionFailure_retriesAndFails() {
        stubFor(put(urlPathEqualTo("/devices/" + DEVICE_ID + "/programs"))
                        .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> mieleDishwasher.startProgram(123).join())
                .hasRootCauseInstanceOf(IOException.class);
    }
}
