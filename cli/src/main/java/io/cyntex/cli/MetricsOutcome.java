package io.cyntex.cli;

import java.util.Map;

/**
 * The outcome of a remote pipeline metrics read ({@code GET /api/pipelines/{id}/metrics}). Either the read
 * found the pipeline's open map of run statistics ({@code name -> value}), or it was refused with a coded
 * reason (a pipeline that has published no observation is {@code monitor.no-observation}), or the server
 * could not be reached. The map is empty when no metric source is wired yet (unavailable), never faked.
 * Sealed so the caller renders each branch without try/catch, mirroring the never-throw transport seam.
 */
sealed interface MetricsOutcome {

    /** The read found the pipeline's open map of run statistics; empty when no source is wired yet. */
    record Found(String pipelineId, Map<String, Long> metrics) implements MetricsOutcome {

        public Found {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements MetricsOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements MetricsOutcome {
    }
}
