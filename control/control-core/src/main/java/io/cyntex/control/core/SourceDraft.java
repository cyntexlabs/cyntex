package io.cyntex.control.core;

import io.cyntex.core.model.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured Source input accepted by the control layer. */
public record SourceDraft(
        String id,
        Metadata metadata,
        String connector,
        Map<String, Object> config,
        String mode,
        List<SourceTableDraft> tables,
        Map<String, Object> options,
        SourceSrs srs,
        Map<String, Object> experimental,
        List<String> clearSecrets) {

    public SourceDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(connector, "connector");
        config = SourceRepresentation.copyJsonMap(config, false);
        tables = tables == null ? null
                : Collections.unmodifiableList(new ArrayList<>(tables));
        options = SourceRepresentation.copyJsonMap(options, true);
        experimental = SourceRepresentation.copyJsonMap(experimental, true);
        clearSecrets = clearSecrets == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(clearSecrets));
    }

    @Override
    public String toString() {
        return "SourceDraft[id=" + id
                + ", connector=" + connector
                + ", configKeys=" + config.keySet()
                + ", clearSecrets=" + clearSecrets
                + "]";
    }

    /** Structured replay-store settings with stable frontend enum spelling. */
    public record SourceSrs(
            String key,
            String retention,
            String schemaEvolution,
            Boolean queryable,
            Boolean enabled) {
    }
}
