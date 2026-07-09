package io.cyntex.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UnavailableConnectionProbeTest {

    @Test
    void reportsNotImplementedForAnyTarget() {
        ProbeVerdict verdict = new UnavailableConnectionProbe()
                .probe(new ProbeTarget("mongodb", Map.of("uri", "mongodb://localhost")));

        assertThat(verdict.outcome()).isEqualTo(ProbeVerdict.Outcome.NOT_IMPLEMENTED);
        assertThat(verdict.detail()).isNotBlank();
    }
}
