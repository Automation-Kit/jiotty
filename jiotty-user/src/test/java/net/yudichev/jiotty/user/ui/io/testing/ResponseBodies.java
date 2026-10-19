package net.yudichev.jiotty.user.ui.io.testing;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.user.ui.io.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

/// Static methods over [ResponseBody] values for tests.
public final class ResponseBodies {
    private ResponseBodies() {
    }

    /// Returns `body` encoded and decoded as UTF-8 text.
    public static String encode(ResponseBody body) {
        return encodeAll(ImmutableList.of(body));
    }

    /// Returns `bodies` encoded one after another, as one stream carries them, and decoded as UTF-8 text.
    public static String encodeAll(List<? extends ResponseBody> bodies) {
        var bytes = new ByteArrayOutputStream();
        for (ResponseBody body : bodies) {
            asUnchecked(() -> body.writeTo(bytes));
        }
        return bytes.toString(UTF_8);
    }
}
