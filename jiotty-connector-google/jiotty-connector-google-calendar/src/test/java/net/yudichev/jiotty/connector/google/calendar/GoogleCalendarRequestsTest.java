package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarRequestsTest {
    @Test
    void execute_returnsResponse(@Mock AbstractGoogleClientRequest<String> request) throws IOException {
        when(request.execute()).thenReturn("response");

        assertThat(GoogleCalendarRequests.execute("fetch the thing", request)).isEqualTo("response");
    }

    @Test
    void execute_rewrapsIoExceptionWithDescription(@Mock AbstractGoogleClientRequest<String> request) throws IOException {
        var cause = new IOException("network down");
        when(request.execute()).thenThrow(cause);

        assertThatThrownBy(() -> GoogleCalendarRequests.execute("fetch the thing", request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Failed to fetch the thing")
                .hasCause(cause);
    }
}
