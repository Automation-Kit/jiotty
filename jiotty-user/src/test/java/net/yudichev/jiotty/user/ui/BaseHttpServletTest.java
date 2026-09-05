package net.yudichev.jiotty.user.ui;

import org.junit.jupiter.api.Test;

import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Verifies [BaseHttpServlet] refuses serialisation on behalf of a subclass, which is the whole reason it exists: the refusal is declared once here, and
/// serialisation has to run it for the subclass's stream as well as its own.
class BaseHttpServletTest {
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

    /// Stands in for the real servlets, which declare no serialisation hooks of their own and rely entirely on the ones above.
    private static final class Subclass extends BaseHttpServlet {}
}
