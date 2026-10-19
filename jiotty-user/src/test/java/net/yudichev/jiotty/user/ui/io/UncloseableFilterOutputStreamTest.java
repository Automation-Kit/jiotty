package net.yudichev.jiotty.user.ui.io;

import net.yudichev.jiotty.user.ui.io.testing.CloseTrackingOutputStream;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static org.assertj.core.api.Assertions.assertThat;

class UncloseableFilterOutputStreamTest {
    @Test
    void passesEveryWriteThroughAndLeavesTheWrappedStreamOpenWhenClosed() {
        var output = new CloseTrackingOutputStream();

        asUnchecked(() -> {
            try (var stream = new UncloseableFilterOutputStream(output)) {
                stream.write('a');
                stream.write("bcd".getBytes(UTF_8), 1, 2);
            }
        });

        assertThat(output.toString(UTF_8)).isEqualTo("acd");
        assertThat(output.isClosed()).isFalse();
    }
}
