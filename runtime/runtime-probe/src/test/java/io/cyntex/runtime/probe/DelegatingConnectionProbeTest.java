package io.cyntex.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.ConnectionTestItem;
import io.cyntex.spi.store.ConnectionTestResult;
import io.cyntex.spi.store.ConnectionTestResult.Outcome;
import io.cyntex.spi.store.ConnectionTester;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DelegatingConnectionProbeTest {

    @Test
    void drivesTheTesterWithTheConfigAndReturnsItsResult() {
        ConnectionConfig config =
                new ConnectionConfig("conn-orders", "mongodb", Map.of("uri", "mongodb://localhost"));
        ConnectionTestResult expected = new ConnectionTestResult(
                "conn-orders",
                "mongodb",
                Outcome.PASSED,
                List.of(new ConnectionTestItem(
                        "Connection", ConnectionTestItem.Status.PASSED, null, null, null, null)),
                1_700_000_000_000L);
        AtomicReference<ConnectionConfig> driven = new AtomicReference<>();
        ConnectionTester tester = seen -> {
            driven.set(seen);
            return expected;
        };

        ConnectionProbe probe = new DelegatingConnectionProbe(tester);
        ConnectionTestResult result = probe.probe(config);

        assertThat(result).isSameAs(expected);
        assertThat(driven.get()).isSameAs(config);
    }

    @Test
    void requiresATester() {
        assertThatThrownBy(() -> new DelegatingConnectionProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
