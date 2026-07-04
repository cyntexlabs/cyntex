package io.cyntex.spi.capture;

import java.util.Objects;

/**
 * One field of a discovered stream: its name and its cyntex type name. {@code type} is null when
 * discovery cannot resolve it.
 */
public record FieldSchema(String name, String type) {

    public FieldSchema {
        Objects.requireNonNull(name, "name");
    }
}
