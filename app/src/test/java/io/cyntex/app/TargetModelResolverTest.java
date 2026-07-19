package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.SourceMode;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.TableRef;
import io.cyntex.spi.sink.TargetField;
import io.cyntex.spi.sink.TargetTable;
import io.cyntex.spi.store.DiscoveredSourceModel;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Coverage for resolving a sink's write-side target model from the discovered source model: the pure mapping
 * of one discovered {@link SourceTable} onto a {@link TargetTable}, where the table-level ordered primary-key
 * column list becomes per-field key flags whose order the sink keys an upsert on.
 */
class TargetModelResolverTest {

    @Test
    void maps_a_discovered_table_to_a_target_table_flagging_the_primary_key() {
        SourceTable orders = new SourceTable(
                "orders",
                List.of(new SourceField("id", "INT"), new SourceField("amount", "DECIMAL")),
                List.of("id"),
                List.of());

        TargetTable target = TargetModelResolver.toTargetTable(orders);

        assertThat(target.name()).isEqualTo("orders");
        assertThat(target.fields()).containsExactly(
                new TargetField("id", "INT", true),
                new TargetField("amount", "DECIMAL", false));
    }

    @Test
    void orders_primary_key_fields_by_key_order_so_the_upsert_key_matches_the_source() {
        SourceTable line = new SourceTable(
                "line",
                List.of(new SourceField("a", "INT"), new SourceField("b", "INT"), new SourceField("c", "INT")),
                List.of("c", "a"),
                List.of());

        TargetTable target = TargetModelResolver.toTargetTable(line);

        // The sink keys an upsert in target-field order, so the key columns must lead in key order (c, a);
        // the non-key fields follow in source order.
        assertThat(target.fields()).containsExactly(
                new TargetField("c", "INT", true),
                new TargetField("a", "INT", true),
                new TargetField("b", "INT", false));
    }

    @Test
    void maps_a_table_with_no_primary_key_to_all_non_key_fields() {
        SourceTable logs = new SourceTable(
                "logs", List.of(new SourceField("msg", "TEXT")), List.of(), List.of());

        TargetTable target = TargetModelResolver.toTargetTable(logs);

        assertThat(target.fields()).containsExactly(new TargetField("msg", "TEXT", false));
    }

    @Test
    void resolves_the_target_from_the_discovered_model_of_the_pipelines_source() {
        InMemoryStorePort store = new InMemoryStorePort();
        store.artifacts().save(cdcSource("src_mysql", "orders"));
        store.artifacts().save(pipeline("p", "src_mysql"));
        store.schemas().save(discovered("src_mysql", "mysql", new SourceTable(
                "orders",
                List.of(new SourceField("id", "INT"), new SourceField("amount", "DECIMAL")),
                List.of("id"),
                List.of())));

        Optional<TargetTable> target = new TargetModelResolver(store).resolve(pipelineArtifact(store, "p"));

        assertThat(target).contains(new TargetTable("orders", List.of(
                new TargetField("id", "INT", true),
                new TargetField("amount", "DECIMAL", false))));
    }

    @Test
    void resolves_to_empty_when_the_source_schema_was_never_discovered() {
        InMemoryStorePort store = new InMemoryStorePort();
        store.artifacts().save(cdcSource("src_mysql", "orders"));
        store.artifacts().save(pipeline("p", "src_mysql"));

        Optional<TargetTable> target = new TargetModelResolver(store).resolve(pipelineArtifact(store, "p"));

        assertThat(target).isEmpty();
    }

    // ---- fixtures ----------------------------------------------------------------------

    private static SourceResource cdcSource(String id, String table) {
        return new SourceResource(id, null, "mysql", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal(table)), null, null, null);
    }

    private static PipelineResource pipeline(String id, String sourceId) {
        return new PipelineResource(id, null, List.of(sourceId), null, null, null, null, null);
    }

    private static PipelineResource pipelineArtifact(InMemoryStorePort store, String id) {
        return (PipelineResource) store.artifacts().get(id).orElseThrow();
    }

    private static DiscoveredSourceModel discovered(String connectionId, String connectorId, SourceTable table) {
        return new DiscoveredSourceModel(connectionId, connectorId, 0L, new SourceModel(List.of(table)));
    }
}
