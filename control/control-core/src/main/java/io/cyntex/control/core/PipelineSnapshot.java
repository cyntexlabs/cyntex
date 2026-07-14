package io.cyntex.control.core;

import io.cyntex.core.lifecycle.TableSnapshot;
import java.util.Map;
import java.util.Objects;

/**
 * The snapshot read face: a pipeline's per-table initial-load progress. Empty outside a snapshot phase
 * or when unavailable; a table with no total reports its total as unavailable rather than faking 0 or
 * 100%.
 */
public record PipelineSnapshot(String pipelineId, Map<String, TableSnapshot> snapshot) {

    public PipelineSnapshot {
        Objects.requireNonNull(pipelineId, "pipelineId");
        snapshot = snapshot == null ? Map.of() : Map.copyOf(snapshot);
    }
}
