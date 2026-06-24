package io.cyntex.core.catalog;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import io.cyntex.core.model.SourceMode;

/**
 * The resolved source modes of a connector and where each one came from. Iteration order follows
 * {@link SourceMode} declaration order so the catalog is deterministic.
 */
public record ModeResolution(Map<SourceMode, ModeSource> bySource) {

    public ModeResolution {
        EnumMap<SourceMode, ModeSource> copy = new EnumMap<>(SourceMode.class);
        if (bySource != null) {
            copy.putAll(bySource);
        }
        bySource = Collections.unmodifiableMap(copy);
    }

    /** The resolved modes, in {@link SourceMode} declaration order. */
    public Set<SourceMode> modes() {
        return bySource.keySet();
    }

    /** Where {@code mode} came from, or {@code null} if the connector does not support it. */
    public ModeSource source(SourceMode mode) {
        return bySource.get(mode);
    }
}
