package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static net.yudichev.jiotty.user.ui.io.testing.ResponseBodies.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ResponseBodyTest {
    @Test
    void theEmptyBodySetsNothingAndEncodesNothing(@Mock HttpServletResponse response) {
        ResponseBody.EMPTY.applyHeadersTo(response);

        verifyNoInteractions(response);
        assertThat(encode(ResponseBody.EMPTY)).isEmpty();
    }
}
