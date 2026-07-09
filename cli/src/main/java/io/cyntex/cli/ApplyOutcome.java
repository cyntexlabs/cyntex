package io.cyntex.cli;

import java.util.List;

/**
 * The outcome of a remote {@code POST /api/artifacts:apply}. Either the batch applied and the server
 * reported one item per artifact (created / updated / unchanged), or it was refused with a coded reason (a
 * validation failure is a {@code dsl.*} code), or the server could not be reached. Sealed so the caller
 * renders each branch without try/catch, mirroring the never-throw transport seam.
 */
sealed interface ApplyOutcome {

    /** The batch applied; one item per submitted artifact, in submission order. */
    record Applied(List<Item> items) implements ApplyOutcome {
        public Applied {
            items = List.copyOf(items);
        }
    }

    /** The server refused the apply with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements ApplyOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements ApplyOutcome {
    }

    /**
     * One artifact's apply result: its id, kind, and how it changed — {@code CREATED} (written),
     * {@code UPDATED} (overwritten) or {@code UNCHANGED} (an identical no-op the server did not write).
     */
    record Item(String id, String kind, String change) {
    }
}
