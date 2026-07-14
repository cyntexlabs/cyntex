package io.cyntex.app;

import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.spi.sink.TargetField;
import io.cyntex.spi.sink.TargetTable;
import io.cyntex.spi.store.DiscoveredSourceModel;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceTable;
import io.cyntex.spi.store.StorePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a sink's write-side target model from the source model discovery persisted for a connection. The
 * table structure a sink creates and the key an upsert matches on come from the upstream source's discovered
 * model, not from the events flowing through - so a target table is built by reading the persisted model for
 * the pipeline's source and mapping the discovered {@link SourceTable} onto a {@link TargetTable}.
 *
 * <p>L1 shape: a pipeline reads a single source of a single table, so the resolved target is that table. When
 * the source's schema has never been discovered the target is absent, and the sink falls back to a bare table
 * id and lets the connector infer structure and keying.
 */
final class TargetModelResolver {

    private final StorePort storePort;

    TargetModelResolver(StorePort storePort) {
        this.storePort = Objects.requireNonNull(storePort, "storePort");
    }

    /**
     * Resolves the write-side target table for a pipeline's sink from the discovered model of the source it
     * reads: the source's single L1 table looked up in its persisted model and mapped to a target table.
     * Empty when the source's schema was never discovered, or when the discovered model does not carry that
     * table.
     */
    Optional<TargetTable> resolve(PipelineResource pipeline) {
        for (String sourceId : pipeline.sources()) {
            SourceResource source = StoredArtifacts.requireSource(storePort.artifacts(), sourceId);
            String table = SourceCaptureResolution.of(source).table();
            Optional<SourceTable> discovered = discoveredTable(sourceId, table);
            if (discovered.isPresent()) {
                return Optional.of(toTargetTable(discovered.get()));
            }
        }
        return Optional.empty();
    }

    /** The named table in the source's persisted discovery model, or empty when neither is present. */
    private Optional<SourceTable> discoveredTable(String connectionId, String table) {
        return storePort.schemas().get(connectionId)
                .map(DiscoveredSourceModel::model)
                .flatMap(model -> model.tables().stream().filter(t -> t.name().equals(table)).findFirst());
    }

    /**
     * Maps one discovered source table onto the write-side target table a sink writes: each field carries over
     * with its source-declared type, and a field named in the table's primary key is flagged so the sink keys
     * an upsert on it. The sink keys the upsert in target-field order, so the key columns lead in the source's
     * key order and the remaining fields follow in source order.
     */
    static TargetTable toTargetTable(SourceTable source) {
        List<String> primaryKey = source.primaryKey();
        List<TargetField> fields = new ArrayList<>(source.fields().size());
        for (String keyColumn : primaryKey) {
            SourceField field = field(source, keyColumn);
            fields.add(new TargetField(field.name(), field.type(), true));
        }
        for (SourceField field : source.fields()) {
            if (!primaryKey.contains(field.name())) {
                fields.add(new TargetField(field.name(), field.type(), false));
            }
        }
        return new TargetTable(source.name(), fields);
    }

    /** The discovered field a key column names; a key naming no discovered field is a broken source model. */
    private static SourceField field(SourceTable source, String name) {
        for (SourceField field : source.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        throw new IllegalStateException(
                "primary key column '" + name + "' is not among the fields of discovered table '" + source.name() + "'");
    }
}
