package io.cyntex.spi.capture;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A resolved capture configuration: which connector to run, the connection settings to run it with,
 * and the streams to read. An immutable value.
 *
 * <p>{@code connectorId} is the catalog id of the connector. {@code settings} are the resolved
 * connection values keyed by the connector's config field names. {@code streams} are the logical
 * stream (table) names to capture; each becomes the {@code src} of the events yielded. An empty
 * {@code streams} means every stream the connector exposes.
 *
 * <p>{@code settings} and {@code streams} are held as unmodifiable defensive copies; a null map or
 * list is normalized to empty.
 */
public record CaptureConfig(String connectorId, Map<String, Object> settings, List<String> streams) {

    public CaptureConfig {
        Objects.requireNonNull(connectorId, "connectorId");
        settings = settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        streams = streams == null ? List.of() : List.copyOf(streams);
    }
}
