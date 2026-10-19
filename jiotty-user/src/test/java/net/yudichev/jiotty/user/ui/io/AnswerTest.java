package net.yudichev.jiotty.user.ui.io;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerTest {
    @Test
    void anAnswerAddsHeadersWithoutLosingTheOnesItHas() {
        Answer answer = Answer.of(OK_200).withHeader("A", "1").withHeader("B", "2").withHeader("A", "3");

        assertThat(answer.headers()).isEqualTo(ImmutableMap.of("A", "3", "B", "2"));
    }

    @Test
    void anAnswerRefusesAStatusOutsideTheHttpRange() {
        assertThatThrownBy(() -> Answer.of(99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Answer.of(600)).isInstanceOf(IllegalArgumentException.class);
    }
}
