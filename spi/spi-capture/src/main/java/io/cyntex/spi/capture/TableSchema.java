package io.cyntex.spi.capture;

import java.util.List;
import java.util.Objects;

/**
 * One discovered stream (table): its logical name and its fields. {@code fields} is held as an
 * unmodifiable copy; a null list is normalized to empty.
 */
public record TableSchema(String name, List<FieldSchema> fields) {

    public TableSchema {
        Objects.requireNonNull(name, "name");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
