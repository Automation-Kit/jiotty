package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.Sha256Digests.encodeUrl;
import static net.yudichev.jiotty.common.lang.Sha256Digests.resetDigest;
import static org.assertj.core.api.Assertions.assertThat;

class Sha256DigestsTest {

    @Test
    void resetDigest_handsBackADigestHoldingNothing() {
        String ofNothing = encodeUrl(resetDigest());
        resetDigest().update("half a value".getBytes(UTF_8));

        assertThat(encodeUrl(resetDigest())).isEqualTo(ofNothing);
    }

    @Test
    void encodeUrl_rendersTheDigestUrlSafely() {
        assertThat(encodeUrl(resetDigest())).hasSize(43)
                                            .matches("[A-Za-z0-9_-]+");
    }

    @Test
    void encodeUrl_leavesTheDigestReadyForItsNextValue() {
        MessageDigest digest = resetDigest();
        digest.update("a value".getBytes(UTF_8));
        String encoded = encodeUrl(digest);

        digest.update("a value".getBytes(UTF_8));

        assertThat(encodeUrl(digest)).isEqualTo(encoded);
    }
}
