package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.List;

import io.cyntex.core.model.SourceMode;

/**
 * One connector's fully resolved catalog row: identity, organising group, the source modes the
 * grammar may pair it with, table-discovery ability, sink capability, whether it can be a push
 * target, the connection config form and the provenance. This is what the wizard, validate and
 * explain read; the build tool generates it, the runtime loader reconstructs it.
 */
public record ConnectorCatalogEntry(
        String id,
        String name,
        String displayName,
        String icon,
        ConnectorGroup group,
        List<SourceMode> modes,
        Discovery discovery,
        SinkCapability sink,
        boolean pushOut,
        List<ConfigField> config,
        Provenance provenance) {

    public ConnectorCatalogEntry {
        modes = modes == null ? List.of() : Collections.unmodifiableList(List.copyOf(modes));
        config = config == null ? List.of() : Collections.unmodifiableList(List.copyOf(config));
    }
}
