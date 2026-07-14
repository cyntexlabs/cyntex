package io.cyntex.spi.store;

import java.util.List;

/**
 * The metadata a source exposes: the streams (tables) discovered on a connection, each with its
 * fields, primary key and indexes. The truth-layer model that discovery normalizes a connector's
 * schema into and that the store persists per connection. An immutable value carrying no
 * connector-framework types.
 *
 * <p>{@code tables} is held as an unmodifiable defensive copy in discovery order; a null list is
 * normalized to empty.
 */
public record SourceModel(List<SourceTable> tables) {

    public SourceModel {
        tables = tables == null ? List.of() : List.copyOf(tables);
    }
}
