package io.cyntex.cli;

import java.util.Map;

/**
 * The outcome of a remote pipeline snapshot read ({@code GET /api/pipelines/{id}/snapshot}). Either the read
 * found the pipeline's per-table initial-load progress ({@code table -> progress}), or it was refused with a
 * coded reason (a pipeline that has published no observation is {@code monitor.no-observation}), or the
 * server could not be reached. The map is empty outside a snapshot phase or when unavailable. Sealed so the
 * caller renders each branch without try/catch, mirroring the never-throw transport seam.
 */
sealed interface SnapshotOutcome {

    /** The read found the pipeline's per-table load progress; empty outside a snapshot phase. */
    record Found(String pipelineId, Map<String, RemoteTableSnapshot> tables) implements SnapshotOutcome {

        public Found {
            tables = tables == null ? Map.of() : Map.copyOf(tables);
        }
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements SnapshotOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements SnapshotOutcome {
    }
}
