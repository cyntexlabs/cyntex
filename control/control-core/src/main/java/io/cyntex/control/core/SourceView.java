package io.cyntex.control.core;

import io.cyntex.core.model.Metadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Secret-redacted structured Source returned by the control layer. */
public record SourceView(
        String id,
        Metadata metadata,
        String connector,
        Map<String, Object> config,
        List<String> configuredSecrets,
        String mode,
        List<SourceTableView> tables,
        Map<String, Object> options,
        SourceDraft.SourceSrs srs,
        Map<String, Object> experimental,
        String contentHash) {

    public SourceView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(connector, "connector");
        Objects.requireNonNull(contentHash, "contentHash");
        config = SourceRepresentation.copyJsonMap(config, false);
        configuredSecrets = configuredSecrets == null ? List.of() : List.copyOf(configuredSecrets);
        tables = tables == null ? null : List.copyOf(tables);
        options = SourceRepresentation.copyJsonMap(options, true);
        experimental = SourceRepresentation.copyJsonMap(experimental, true);
    }

    @Override
    public String toString() {
        return "SourceView[id=" + id
                + ", connector=" + connector
                + ", configuredSecrets=" + configuredSecrets
                + ", contentHash=" + contentHash
                + "]";
    }
}
