package io.cyntex.spi.sink;

import java.io.Serializable;

/**
 * One field of the resolved target table a sink writes to: its name, its type, and whether it is part
 * of the primary key. An immutable value.
 *
 * <p>{@code type} is the target store's own type token for the field — the string a connector reads to
 * build the column and to coerce each row value to it. It may be null when the type could not be
 * resolved; a null type leaves the connector to infer one. {@code primaryKey} marks a field as part of
 * the key an upsert matches on; the key's column order follows the field order in the {@link
 * TargetTable}.
 *
 * <p>Serializable so a resolved model travels with the sink factory the engine ships onto the DAG.
 */
public record TargetField(String name, String type, boolean primaryKey) implements Serializable {

    public TargetField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("target field name must be non-blank");
        }
    }
}
