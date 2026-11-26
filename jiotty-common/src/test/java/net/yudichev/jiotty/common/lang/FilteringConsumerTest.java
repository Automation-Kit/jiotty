package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static net.yudichev.jiotty.common.lang.FilteringConsumer.filtering;
import static org.assertj.core.api.Assertions.assertThat;

class FilteringConsumerTest {
    @Test
    void passesTheValuesThatTheFilterAccepts_inOrder() {
        List<String> received = new ArrayList<>();
        FilteringConsumer<String> consumer = filtering(received::add, Objects::nonNull);

        consumer.accept("a");
        consumer.accept(null);
        consumer.accept("b");

        assertThat(received).containsExactly("a", "b");
    }
}
