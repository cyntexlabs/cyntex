package io.cyntex.core.lifecycle;

/**
 * The persisted state of a pipeline run-unit. One pipeline is at most one running instance, so this
 * is the state of that instance. The four user verbs ({@link LifecycleVerb}) drive the intent
 * transitions between these states; the transition table lives in {@link LifecycleMachine}.
 */
public enum PipelineState {

    /** Applied and stored, but never run. */
    NEW,

    /** Executing — consuming and moving data. */
    RUNNING,

    /** Execution stopped with offset / state <em>retained</em>; resumable from where it left off. */
    PAUSED,

    /** Execution stopped with offset / state <em>cleared</em> — the same clean baseline as never-run. */
    STOPPED,

    /**
     * Terminal state of a <em>bounded</em> pipeline whose source has been fully read. Entered by the
     * converge side when the source is exhausted, not by a user verb; for {@code start} it behaves
     * exactly like {@link #STOPPED}.
     */
    COMPLETED,

    /**
     * A run whose data-plane job died on its own — the observable error state. Entered by the converge
     * side when a pipeline it believes {@link #RUNNING} is found to have a failed job, never by a user
     * verb, so such a job reports as failed rather than as an eternal RUNNING moving no data. It is not
     * re-driven back toward RUNNING (that would restart the dead job every tick); the user recovers by
     * stopping it — which clears it to {@link #STOPPED} — then starting a fresh run.
     */
    FAILED
}
