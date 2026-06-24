package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import io.cyntex.core.model.SourceMode;

/**
 * Where a catalog entry came from and how trustworthy each mode is. The repo SHA, spec path and
 * content hash are stamped by the build tool; {@code pdkApiVersion}/{@code requiredLevel} are
 * reserved slots (null on the bundled path, filled by server register). {@code modeSource} records
 * per mode whether it was derived from capabilities or declared, so the ingest report can audit it.
 */
public record Provenance(
        String connectorRepoSha,
        String specPath,
        String specContentHash,
        String pdkApiVersion,
        String requiredLevel,
        Map<SourceMode, ModeSource> modeSource) {

    public Provenance {
        EnumMap<SourceMode, ModeSource> copy = new EnumMap<>(SourceMode.class);
        if (modeSource != null) {
            copy.putAll(modeSource);
        }
        modeSource = Collections.unmodifiableMap(copy);
    }
}
