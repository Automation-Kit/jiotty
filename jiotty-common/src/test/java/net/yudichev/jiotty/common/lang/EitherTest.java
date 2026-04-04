package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EitherTest {
    @Test
    void mapsLeft() {
        assertThat(Either.<String, String>left("Value").<String>map(String::toUpperCase, String::toLowerCase)).isEqualTo("VALUE");
    }

    @Test
    void mapsRight() {
        assertThat(Either.<String, String>right("Value").<String>map(String::toUpperCase, String::toLowerCase)).isEqualTo("value");
    }

    @Test
    void mapsLeftNull() {
        assertThat(Either.<String, String>left(null).<String>map(_ -> "left", _ -> "right")).isEqualTo("left");
    }

    @Test
    void mapsRightNull() {
        assertThat(Either.<String, String>right(null).<String>map(_ -> "left", _ -> "right")).isEqualTo("right");
    }

    @Test
    void mapLeft() {
        assertThat(Either.<String, String>left("left").mapLeft(String::toUpperCase)).isEqualTo(Either.left("LEFT"));
        assertThat(Either.<String, String>right("right").mapLeft(String::toUpperCase)).isEqualTo(Either.right("right"));
    }

    @Test
    void mapRight() {
        assertThat(Either.<String, String>left("left").mapRight(String::toUpperCase)).isEqualTo(Either.left("left"));
        assertThat(Either.<String, String>right("right").mapRight(String::toUpperCase)).isEqualTo(Either.right("RIGHT"));
    }

    @Test
    void leftOrThrowReturnsLeftWhenPresent() {
        assertThat(Either.<String, String>left("value").leftOrThrow(IllegalStateException::new)).isEqualTo("value");
    }

    @Test
    void leftOrThrowThrowsWhenRight() {
        assertThatThrownBy(() -> Either.<String, String>right("error").leftOrThrow(IllegalStateException::new))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error");
    }

    @Test
    void rightOrThrowReturnsRightWhenPresent() {
        assertThat(Either.<String, String>right("value").rightOrThrow(IllegalStateException::new)).isEqualTo("value");
    }

    @Test
    void rightOrThrowThrowsWhenLeft() {
        assertThatThrownBy(() -> Either.<String, String>left("error").rightOrThrow(IllegalStateException::new))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("error");
    }
}