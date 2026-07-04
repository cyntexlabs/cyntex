package io.cyntex.spi.capture;

import java.util.List;

/**
 * The streams and fields a source exposes. {@code tables} is held as an unmodifiable copy; a null
 * list is normalized to empty.
 */
public record DiscoveredSchema(List<TableSchema> tables) {

    public DiscoveredSchema {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
