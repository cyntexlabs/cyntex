package io.cyntex.spi.sink;

import java.io.Serializable;
import java.util.List;

/**
 * The resolved model of the table a sink writes to: its name and its fields in order. An immutable
 * value the sink turns into the store's own table descriptor — the fields become columns, and the
 * fields flagged {@link TargetField#primaryKey} become the key an upsert matches on, in field order.
 *
 * <p>This is the write-side target model, already resolved (renames applied, primary key chosen), not
 * the source model as discovered. {@code fields} is held as an unmodifiable defensive copy; a null
 * list is normalized to empty. An empty field list leaves the target structure to the connector.
 *
 * <p>Serializable so a resolved model travels with the sink factory the engine ships onto the DAG.
 */
public record TargetTable(String name, List<TargetField> fields) implements Serializable {

    public TargetTable {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("target table name must be non-blank");
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
