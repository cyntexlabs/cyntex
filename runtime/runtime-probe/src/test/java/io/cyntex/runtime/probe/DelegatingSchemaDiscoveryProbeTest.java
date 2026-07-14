package io.cyntex.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.SchemaDiscoverer;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DelegatingSchemaDiscoveryProbeTest {

    @Test
    void drivesTheDiscovererWithTheConfigAndReturnsItsModel() {
        ConnectionConfig config =
                new ConnectionConfig("conn-orders", "mysql", Map.of("host", "db.local"));
        SourceModel expected = new SourceModel(List.of(
                new SourceTable("orders", List.of(new SourceField("id", "bigint")), List.of("id"), List.of())));
        AtomicReference<ConnectionConfig> driven = new AtomicReference<>();
        SchemaDiscoverer discoverer = seen -> {
            driven.set(seen);
            return expected;
        };

        SchemaDiscoveryProbe probe = new DelegatingSchemaDiscoveryProbe(discoverer);
        SourceModel model = probe.discover(config);

        assertThat(model).isSameAs(expected);
        assertThat(driven.get()).isSameAs(config);
    }

    @Test
    void requiresADiscoverer() {
        assertThatThrownBy(() -> new DelegatingSchemaDiscoveryProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
