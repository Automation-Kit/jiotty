package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletionException;

import static net.yudichev.jiotty.common.lang.MoreThrowables.findInCausalChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoreThrowablesTest {
    @Test
    void findsTheFirstLinkOfTheTypeInTheCausalChain() {
        var outerIo = new IOException("outer", new IOException("inner"));
        var unchecked = new UncheckedIOException(outerIo);
        var failure = new CompletionException(unchecked);

        assertThat(findInCausalChain(failure, UncheckedIOException.class)).containsSame(unchecked);
        assertThat(findInCausalChain(failure, IOException.class)).containsSame(outerIo);
    }

    @Test
    void findsTheThrowableItself() {
        var failure = new IllegalStateException("bad");

        assertThat(findInCausalChain(failure, IllegalStateException.class)).containsSame(failure);
    }

    @Test
    void aChainLoopingBackOnItselfIsRejectedRatherThanWalkedForever() {
        var first = new IOException("first");
        var second = new IOException("second", first);
        first.initCause(second);

        assertThatThrownBy(() -> findInCausalChain(new RuntimeException(first), IllegalStateException.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Loop");
    }

    @Test
    void aChainWithoutTheTypeFindsNothing() {
        assertThat(findInCausalChain(new RuntimeException(new IOException("reset")), IllegalStateException.class)).isEmpty();
    }
}
