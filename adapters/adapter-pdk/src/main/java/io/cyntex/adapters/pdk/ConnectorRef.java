package io.cyntex.adapters.pdk;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where and how to load one connector: its isolated classpath (the connector jar plus any bundled
 * dependencies), its entry class name, and its declared PDK API compatibility — a version and/or an
 * already-derived level, either of which may be {@code null}. Resolving a connector id to this ref
 * (from the seed directory today, the distribution store later) is a separate concern; the bridge only
 * consumes the ref.
 */
public record ConnectorRef(List<Path> classpath, String className, String pdkApiVersion, String requiredLevel) {

    public ConnectorRef {
        classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
        Objects.requireNonNull(className, "className");
    }
}
