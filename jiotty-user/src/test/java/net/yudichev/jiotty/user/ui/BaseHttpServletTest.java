package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Verifies [BaseHttpServlet] refuses serialisation on behalf of a subclass, which is why it exists: the refusal is declared once here, and serialisation has
/// to run it for the subclass's stream as well as its own. Also verifies it answers a dispatch that throws after its request went async.
@ExtendWith(MockitoExtension.class)
class BaseHttpServletTest {
    private static final IllegalStateException HANDLER_FAILURE = new IllegalStateException("defective handler");

    private final TestAdminAlertService alertService = new TestAdminAlertService();
    private final Map<String, Object> requestAttributes = new HashMap<>();
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @Test
    void refusesToSerialiseASubclass() {
        var servlet = new Subclass();

        assertThatThrownBy(() -> {
            try (var objectOutputStream = new ObjectOutputStream(OutputStream.nullOutputStream())) {
                objectOutputStream.writeObject(servlet);
            }
        }).isInstanceOf(NotSerializableException.class)
          .hasMessageContaining(Subclass.class.getName());
    }

    /// The container answers nothing once a request has gone async, so the client would wait forever without this answer.
    @Test
    void answersADispatchThatThrewAfterItsRequestWentAsync() {
        var asyncContext = new FakeAsyncContext(request, response);
        when(request.startAsync()).thenReturn(asyncContext);
        when(request.getMethod()).thenReturn("GET");
        recordRequestAttributes();
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(new CapturingServletOutputStream()));

        asUnchecked(() -> new ThrowingAfterGoingAsyncServlet().service(request, response));

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(alertService.activeAlertsById()).hasSize(1);
    }

    /// A request that never went async is answered by the container, which turns the throw into its own error response.
    @Test
    void leavesADispatchThatThrewSynchronouslyToTheContainer() {
        when(request.getMethod()).thenReturn("GET");

        assertThatThrownBy(() -> new ThrowingServlet().service(request, response)).isSameAs(HANDLER_FAILURE);
    }

    private void recordRequestAttributes() {
        doAnswer(invocation -> requestAttributes.put(invocation.getArgument(0), invocation.getArgument(1))).when(request).setAttribute(anyString(), any());
        lenient().when(request.getAttribute(anyString())).thenAnswer(invocation -> requestAttributes.get(invocation.<String>getArgument(0)));
    }

    /// Stands in for the real servlets, which declare no serialisation hooks of their own and rely entirely on the ones above.
    private static final class Subclass extends BaseHttpServlet {}

    private final class ThrowingAfterGoingAsyncServlet extends BaseHttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            AsyncExchange.start(alertService, request, response, Deadline.none());
            throw HANDLER_FAILURE;
        }
    }

    private static final class ThrowingServlet extends BaseHttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            throw HANDLER_FAILURE;
        }
    }
}
