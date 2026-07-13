package io.cyntex.spi.sink;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A resolved sink configuration: which connector to run, the connection settings to run it with, and
 * the write intent (mode and DDL policy) to apply. An immutable value.
 *
 * <p>{@code connectorId} is the catalog id of the connector. {@code settings} are the resolved
 * connection values keyed by the connector's config field names. {@code writeMode} chooses how rows
 * land ({@link WriteMode#APPEND} / {@link WriteMode#UPSERT}); {@code ddl} chooses what happens to a
 * schema change reaching the sink ({@link DdlPolicy#APPLY} / {@link DdlPolicy#IGNORE} /
 * {@link DdlPolicy#FAIL}). {@code writeMode} and {@code ddl} are resolved values — any authoring
 * default is already applied.
 *
 * <p>{@code target} is the resolved model of the table to write — its columns and primary key. It is
 * what an upsert keys on and what a create-table builds, already resolved (renames applied, key
 * chosen). It is null when no target model was resolved; the sink then falls back to a bare table id
 * and leaves structure and keying to the connector.
 *
 * <p>{@code settings} is held as an unmodifiable defensive copy; a null map is normalized to empty.
 */
public record SinkConfig(
        String connectorId, Map<String, Object> settings, WriteMode writeMode, DdlPolicy ddl, TargetTable target) {

    public SinkConfig {
        Objects.requireNonNull(connectorId, "connectorId");
        Objects.requireNonNull(writeMode, "writeMode");
        Objects.requireNonNull(ddl, "ddl");
        settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    /** A config with no resolved target model — the sink falls back to a bare table id. */
    public SinkConfig(String connectorId, Map<String, Object> settings, WriteMode writeMode, DdlPolicy ddl) {
        this(connectorId, settings, writeMode, ddl, null);
    }
}
