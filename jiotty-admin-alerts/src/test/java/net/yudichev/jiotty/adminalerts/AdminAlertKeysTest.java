package net.yudichev.jiotty.adminalerts;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAlertKeysTest {
    @Test
    void derive_isStableForFixedInputs() {
        String a = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("a", "1", "b", "2"));
        String b = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("a", "1", "b", "2"));

        assertThat(a).isEqualTo(b).startsWith("auto:");
    }

    @Test
    void derive_ignoresLabelEntryOrder() {
        Map<String, String> insertionOrderA = new java.util.LinkedHashMap<>();
        insertionOrderA.put("a", "1");
        insertionOrderA.put("b", "2");
        Map<String, String> insertionOrderB = new java.util.LinkedHashMap<>();
        insertionOrderB.put("b", "2");
        insertionOrderB.put("a", "1");
        Map<String, String> sorted = new TreeMap<>(insertionOrderA);

        String fromAB = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, insertionOrderA);
        String fromBA = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, insertionOrderB);
        String fromSorted = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, sorted);

        assertThat(fromAB).isEqualTo(fromBA).isEqualTo(fromSorted);
    }

    @Test
    void derive_distinctOnAnyDifferingField() {
        String base = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("a", "1"));

        assertThat(AdminAlertKeys.derive("other", AdminAlertSeverity.ERROR, Map.of("a", "1")))
                .isNotEqualTo(base);
        assertThat(AdminAlertKeys.derive("title", AdminAlertSeverity.WARNING, Map.of("a", "1")))
                .isNotEqualTo(base);
        assertThat(AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("a", "2")))
                .isNotEqualTo(base);
        assertThat(AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("a", "1", "b", "2")))
                .isNotEqualTo(base);
    }

    @Test
    void derive_emptyLabelsDistinctFromAnyLabel() {
        String empty = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of());
        String oneLabel = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("a", "1"));

        assertThat(empty).isNotEqualTo(oneLabel);
    }

    @Test
    void derive_distinguishesKeyValueBoundaries() {
        // {"ab": ""} should NOT collide with {"a": "b"}
        String a = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("ab", ""));
        String b = AdminAlertKeys.derive("title", AdminAlertSeverity.ERROR, Map.of("a", "b"));

        assertThat(a).isNotEqualTo(b);
    }
}
