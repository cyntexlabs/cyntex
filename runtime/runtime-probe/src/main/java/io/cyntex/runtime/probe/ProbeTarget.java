package io.cyntex.runtime.probe;

import java.util.Map;

/**
 * What to probe: a connector and the settings that address one connection. Minimal by intent — the
 * probe reads only what it needs to open the connection. The real probe in a later slice widens this
 * as the connector plane defines how a connection is addressed.
 *
 * <p>The settings map is defensively copied and unmodifiable.
 */
public record ProbeTarget(String connectorId, Map<String, String> settings) {

    public ProbeTarget {
        if (connectorId == null || connectorId.isBlank()) {
            throw new IllegalArgumentException("connectorId must be present");
        }
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }
}
