package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.spi.store.DiscoveredSourceModel;
import io.cyntex.spi.store.SchemaStore;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceIndex;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SchemaQueryServiceTest {

    private static final DiscoveredSourceModel DISCOVERED = new DiscoveredSourceModel(
            "conn-orders",
            "mysql",
            1_700_000_000_000L,
            new SourceModel(List.of(new SourceTable(
                    "orders",
                    List.of(new SourceField("id", "bigint"), new SourceField("note", null)),
                    List.of("id"),
                    List.of(new SourceIndex("pk_orders", List.of("id"), true))))));

    @Test
    void findProjectsTheStoredEnvelopeOntoTheSurfaceReport() {
        SchemaQueryService service = new SchemaQueryService(storeReturning(DISCOVERED));

        Optional<SchemaReport> found = service.find("conn-orders");

        assertThat(found).isPresent();
        SchemaReport report = found.orElseThrow();
        assertThat(report.connectionId()).isEqualTo("conn-orders");
        assertThat(report.connectorId()).isEqualTo("mysql");
        assertThat(report.discoveredAt()).isEqualTo(1_700_000_000_000L);
        assertThat(report.tables()).hasSize(1);
        SchemaReport.Table orders = report.tables().get(0);
        assertThat(orders.name()).isEqualTo("orders");
        assertThat(orders.fields()).extracting(SchemaReport.Field::name).containsExactly("id", "note");
        assertThat(orders.fields()).extracting(SchemaReport.Field::type).containsExactly("bigint", null);
        assertThat(orders.primaryKey()).containsExactly("id");
        assertThat(orders.indexes())
                .extracting(SchemaReport.Index::name, SchemaReport.Index::unique)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("pk_orders", true));
    }

    @Test
    void findOfANeverDiscoveredConnectionIsEmpty() {
        SchemaQueryService service = new SchemaQueryService(storeReturning(null));

        assertThat(service.find("never-discovered")).isEmpty();
    }

    @Test
    void requiresAStoreAndAConnectionId() {
        assertThatThrownBy(() -> new SchemaQueryService(null)).isInstanceOf(NullPointerException.class);
        SchemaQueryService service = new SchemaQueryService(storeReturning(null));
        assertThatThrownBy(() -> service.find(null)).isInstanceOf(NullPointerException.class);
    }

    private static SchemaStore storeReturning(DiscoveredSourceModel stored) {
        return new SchemaStore() {
            @Override
            public void save(DiscoveredSourceModel discovered) {
                throw new UnsupportedOperationException("read-only in this test");
            }

            @Override
            public Optional<DiscoveredSourceModel> get(String connectionId) {
                return Optional.ofNullable(stored);
            }
        };
    }
}
