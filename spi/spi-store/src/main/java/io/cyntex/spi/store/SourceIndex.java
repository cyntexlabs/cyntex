package io.cyntex.spi.store;

import java.util.List;
import java.util.Objects;

/**
 * One index on a discovered stream: its name, the ordered column names it covers and whether it is
 * unique. An immutable value.
 *
 * <p>{@code name} is always present. {@code fields} is held as an unmodifiable defensive copy in index
 * order; a null list is normalized to empty.
 */
public record SourceIndex(String name, List<String> fields, boolean unique) {

    public SourceIndex {
        Objects.requireNonNull(name, "name");
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
