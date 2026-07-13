package io.cyntex.cli;

import java.util.List;
import java.util.Objects;

/**
 * One connector as the online catalog lists it: its id, display name, organising group, the source
 * modes it may be paired with, whether it can be a sink, and whether it came from the bundled snapshot
 * or was registered at runtime. The response-side value the {@code connectors} verb decodes from the
 * server's JSON. The CLI carries no shared control type (rule R6: it reaches the server over HTTP only),
 * so this mirrors the server's connector-summary shape independently.
 */
record CatalogConnector(String id, String name, String group, List<String> modes, boolean sink, String origin) {

    CatalogConnector {
        Objects.requireNonNull(id, "id");
        modes = modes == null ? List.of() : List.copyOf(modes);
    }
}
