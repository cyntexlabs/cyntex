package io.cyntex.spi.store;

import java.util.List;
import java.util.Objects;

/**
 * One discovered stream (table): its logical name, its fields, its primary-key column names and its
 * indexes. An immutable value.
 *
 * <p>{@code name} is always present. {@code primaryKey} is the ordered list of key column names (empty
 * when the source declares none). {@code fields}, {@code primaryKey} and {@code indexes} are each held
 * as an unmodifiable defensive copy; a null list is normalized to empty.
 */
public record SourceTable(
        String name,
        List<SourceField> fields,
        List<String> primaryKey,
        List<SourceIndex> indexes) {

    public SourceTable {
        Objects.requireNonNull(name, "name");
        fields = fields == null ? List.of() : List.copyOf(fields);
        primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
    }
}
