package io.cyntex.adapters.pdk;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where and how to load one connector: its isolated classpath (the connector jar plus any bundled
 * dependencies), its entry class name, its declared PDK API compatibility — a version and/or an
 * already-derived level, either of which may be {@code null} — and its raw spec JSON, whose
 * {@code dataTypes} the bridge reads to map a discovered field's database type onto a PDK type before a
 * read. The spec may be {@code null} when it is not available or not needed (a synthetic connector).
 * Resolving a connector id to this ref (from the seed directory today, the distribution store later) is
 * a separate concern; the bridge only consumes the ref.
 */
public record ConnectorRef(
        List<Path> classpath, String className, String pdkApiVersion, String requiredLevel, String spec) {

    public ConnectorRef {
        classpath = List.copyOf(Objects.requireNonNull(classpath, "classpath"));
        Objects.requireNonNull(className, "className");
    }

    /** A ref with no spec: its type mapping is not needed or not available. */
    public ConnectorRef(List<Path> classpath, String className, String pdkApiVersion, String requiredLevel) {
        this(classpath, className, pdkApiVersion, requiredLevel, null);
    }
}
