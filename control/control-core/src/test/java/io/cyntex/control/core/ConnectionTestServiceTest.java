package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.runtime.probe.ConnectionProbe;
import io.cyntex.runtime.probe.ProbeTarget;
import io.cyntex.runtime.probe.ProbeVerdict;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConnectionTestServiceTest {

    @Test
    void delegatesToTheRuntimeProbeAndReturnsItsVerdict() {
        AtomicReference<ProbeTarget> seen = new AtomicReference<>();
        ProbeVerdict expected = ProbeVerdict.notImplemented("stub");
        ConnectionProbe probe = target -> {
            seen.set(target);
            return expected;
        };
        ConnectionTestService service = new ConnectionTestService(probe);

        ProbeTarget target = new ProbeTarget("mongodb", Map.of("uri", "mongodb://localhost"));
        ProbeVerdict verdict = service.test(target);

        assertThat(verdict).isSameAs(expected);
        assertThat(seen.get()).isSameAs(target);
    }

    @Test
    void requiresAProbe() {
        assertThatThrownBy(() -> new ConnectionTestService(null))
                .isInstanceOf(NullPointerException.class);
    }
}
