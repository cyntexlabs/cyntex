package io.cyntex.cli;

/**
 * The outcome of a remote pipeline lifecycle verb ({@code POST /api/pipelines/{id}:{verb}}). Either the
 * verb was accepted and the server reported the pipeline's new desired state (a target state at a
 * revision), or it was refused with a coded reason (an unknown pipeline, a transition the state machine
 * forbids, or a start/resume at a stale revision), or the server could not be reached. Sealed so the caller
 * renders each branch without try/catch, mirroring the never-throw transport seam. The CLI carries no
 * shared control type (rule R6: it reaches the server over HTTP only), so this mirrors the server's
 * desired-state shape independently.
 */
sealed interface LifecycleOutcome {

    /** The verb was accepted; the server's new desired state — the target state at the revision it runs at. */
    record Accepted(String pipelineId, String targetState, String revision) implements LifecycleOutcome {
    }

    /** The server refused the verb with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements LifecycleOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements LifecycleOutcome {
    }
}
