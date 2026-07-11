package io.cyntex.spi.store;

import java.util.Objects;

/**
 * One field of a discovered stream: its name and its source-declared data type. An immutable value.
 *
 * <p>{@code name} is always present. {@code type} is the type the connector reported for the field
 * and is null when discovery cannot resolve it; mapping it onto the cyntex type namespace is a
 * separate normalization step, not done here.
 */
public record SourceField(String name, String type) {

    public SourceField {
        Objects.requireNonNull(name, "name");
    }
}
