package net.yudichev.jiotty.connector.icloud.status;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.createSourceFailingEveryRead;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.connector.icloud.status.AppleSystemStatusSourceImpl.STATUS_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BodySizeLimitInterceptorTest {
    private static final int LIMIT_BYTES = 8;

    private final BodySizeLimitInterceptor interceptor = new BodySizeLimitInterceptor(LIMIT_BYTES);
    @Mock
    private Interceptor.Chain chain;
    private boolean bodySourceClosed;

    @Test
    void aBodyAtTheLimit_passesThroughStillReadable() {
        String body = "x".repeat(LIMIT_BYTES);
        stubResponseWithBody(body);

        Response interceptedResponse = getAsUnchecked(() -> interceptor.intercept(chain));

        assertThat(getAsUnchecked(() -> interceptedResponse.body().string())).isEqualTo(body);
    }

    @Test
    void aBodyOverTheLimit_failsTheCallAndClosesTheResponse() {
        stubResponseWithBody("x".repeat(LIMIT_BYTES + 1));

        assertThatThrownBy(() -> interceptor.intercept(chain)).isInstanceOf(IOException.class).hasMessageContaining(LIMIT_BYTES + " bytes");
        assertThat(bodySourceClosed).isTrue();
    }

    @Test
    void aBodyThatFailsToRead_failsTheCallAndClosesTheResponse() {
        var readFailure = new IOException("unexpected end of stream");
        stubResponseWithBodySource(createSourceFailingEveryRead(readFailure), LIMIT_BYTES);

        assertThatThrownBy(() -> interceptor.intercept(chain)).isSameAs(readFailure);
        assertThat(bodySourceClosed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aLimitThatIsNotPositive_isRejected(long limitBytes) {
        assertThatThrownBy(() -> new BodySizeLimitInterceptor(limitBytes)).isInstanceOf(IllegalArgumentException.class);
    }

    // The buffer is the body of the stubbed response, which the code reading that response closes.
    @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
    private void stubResponseWithBody(String body) {
        stubResponseWithBodySource(new Buffer().writeUtf8(body), body.length());
    }

    private void stubResponseWithBodySource(Source bodySource, long contentLength) {
        Request request = STATUS_REQUEST;
        var closeRecordingSource = new ForwardingSource(bodySource) {
            @Override
            public void close() throws IOException {
                bodySourceClosed = true;
                super.close();
            }
        };
        ResponseBody responseBody = ResponseBody.create(Okio.buffer(closeRecordingSource), null, contentLength);
        asUnchecked(() -> {
            when(chain.request()).thenReturn(request);
            when(chain.proceed(request)).thenReturn(response(request, OK_200, responseBody));
        });
    }
}
