package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stored unit of schema discovery: the normalized source model wrapped with the identity and
 * freshness the read face reports — connection id (the store key), connector id (display), and the
 * discovery time. A pure immutable value.
 */
class DiscoveredSourceModelTest {

    private static SourceModel model() {
        return new SourceModel(List.of(
                new SourceTable("orders", List.of(new SourceField("id", "bigint")), List.of("id"), List.of())));
    }

    @Test
    void carriesConnectionIdConnectorIdDiscoveredAtAndModel() {
        DiscoveredSourceModel discovered =
                new DiscoveredSourceModel("conn-mongo-orders", "mongodb", 1783998000000L, model());

        assertThat(discovered.connectionId()).isEqualTo("conn-mongo-orders");
        assertThat(discovered.connectorId()).isEqualTo("mongodb");
        assertThat(discovered.discoveredAt()).isEqualTo(1783998000000L);
        assertThat(discovered.model().tables()).extracting(SourceTable::name).containsExactly("orders");
    }

    @Test
    void requiresConnectionIdConnectorIdAndModel() {
        assertThatNullPointerException()
                .isThrownBy(() -> new DiscoveredSourceModel(null, "mongodb", 1L, model()));
        assertThatNullPointerException()
                .isThrownBy(() -> new DiscoveredSourceModel("c", null, 1L, model()));
        assertThatNullPointerException()
                .isThrownBy(() -> new DiscoveredSourceModel("c", "mongodb", 1L, null));
    }
}
