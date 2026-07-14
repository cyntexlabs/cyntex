package io.cyntex.control.core;

import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.model.SourceMode;

import java.util.List;

/** The normalized connector detail a dynamic Source form consumes. */
public record ConnectorDetail(
        String id,
        String name,
        String icon,
        String group,
        List<String> modes,
        String discovery,
        boolean sink,
        boolean pushOut,
        String origin,
        List<ConnectorConfigFieldView> config) {

    static ConnectorDetail of(ConnectorCatalogEntry entry, String origin) {
        return new ConnectorDetail(
                entry.id(),
                entry.displayName(),
                entry.icon(),
                entry.group().yaml(),
                entry.modes().stream().map(SourceMode::yaml).toList(),
                entry.discovery().yaml(),
                entry.sink().capable(),
                entry.pushOut(),
                origin,
                entry.config().stream().map(ConnectorConfigFieldView::of).toList());
    }
}
