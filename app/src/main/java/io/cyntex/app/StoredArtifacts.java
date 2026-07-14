package io.cyntex.app;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.spi.store.ArtifactStore;
import java.util.Map;

/**
 * How the data-plane assembly resolves the artifacts a start actuates from the store. A start names a
 * pipeline id, so a missing artifact or an id that names another kind is a user-facing, diagnosable failure
 * carried through the error-code system. A source a pipeline references, by contrast, is an internal
 * invariant of an already-applied pipeline: its absence or wrong kind is a builder-side defect and crashes
 * bare rather than being laundered into a code. The two resolutions live in one place so the reader and the
 * capture side load them the same way.
 */
final class StoredArtifacts {

    private StoredArtifacts() {
    }

    /** The stored pipeline for the id, or a coded diagnostic when it is absent or names another kind. */
    static PipelineResource requirePipeline(ArtifactStore artifacts, String pipelineId) {
        Resource resource = artifacts.get(pipelineId).orElseThrow(() -> new CyntexException(
                ActuationError.PIPELINE_NOT_FOUND, Map.of("pipeline", pipelineId), null));
        if (!(resource instanceof PipelineResource pipeline)) {
            throw new CyntexException(ActuationError.NOT_A_PIPELINE,
                    Map.of("pipeline", pipelineId, "kind", resource.kind()), null);
        }
        return pipeline;
    }

    /** The stored source a pipeline references, or a bare crash when it is absent or names another kind. */
    static SourceResource requireSource(ArtifactStore artifacts, String sourceId) {
        Resource resource = artifacts.get(sourceId).orElseThrow(() -> new IllegalStateException(
                "source '" + sourceId + "' referenced by a pipeline is not in the store"));
        if (!(resource instanceof SourceResource source)) {
            throw new IllegalStateException("resource '" + sourceId
                    + "' referenced as a source is a '" + resource.kind() + "'");
        }
        return source;
    }
}
