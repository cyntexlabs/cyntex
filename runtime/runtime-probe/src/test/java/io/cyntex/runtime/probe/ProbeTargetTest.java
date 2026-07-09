package io.cyntex.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProbeTargetTest {

    @Test
    void copiesSettingsDefensivelyAndKeepsThemUnmodifiable() {
        Map<String, String> source = new HashMap<>();
        source.put("uri", "mongodb://localhost");
        ProbeTarget target = new ProbeTarget("mongodb", source);

        source.put("uri", "tampered");

        assertThat(target.settings()).containsExactly(Map.entry("uri", "mongodb://localhost"));
        assertThatThrownBy(() -> target.settings().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullSettingsBecomeEmpty() {
        assertThat(new ProbeTarget("mongodb", null).settings()).isEmpty();
    }

    @Test
    void rejectsBlankConnectorId() {
        assertThatThrownBy(() -> new ProbeTarget(" ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
