package io.cyntex.cli;

import java.util.Map;

/**
 * The outcome of a remote pipeline metrics read ({@code GET /api/pipelines/{id}/metrics}). Either the read
 * found the pipeline's open map of numeric run statistics ({@code name -> value}) plus its per-table source
 * positions ({@code table -> opaque srcpos}), or it was refused with a coded reason (a pipeline that has
 * published no observation is {@code monitor.no-observation}), or the server could not be reached. Either map
 * is empty when its source is not wired yet (unavailable), never faked. Sealed so the caller renders each
 * branch without try/catch, mirroring the never-throw transport seam.
 */
sealed interface MetricsOutcome {

    /**
     * The read found the pipeline's open map of numeric run statistics and its per-table source positions;
     * either map is empty when its source is not wired yet.
     */
    record Found(String pipelineId, Map<String, Long> metrics, Map<String, String> perTableOffset)
            implements MetricsOutcome {

        public Found {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            perTableOffset = perTableOffset == null ? Map.of() : Map.copyOf(perTableOffset);
        }

        /** Numeric stats only, no per-table positions — the shape callers used before positions were surfaced. */
        Found(String pipelineId, Map<String, Long> metrics) {
            this(pipelineId, metrics, Map.of());
        }
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements MetricsOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements MetricsOutcome {
    }
}
