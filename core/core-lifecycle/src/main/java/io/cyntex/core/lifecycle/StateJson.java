package io.cyntex.core.lifecycle;

/**
 * The single authority for the actual-state wire form: how a {@link PipelineState} is encoded into
 * the opaque {@code stateJson} the fencing {@link CheckpointDoc} carries. The converge side writes
 * this form; a monitoring read face reads it back. Keeping the encoding in one place means the
 * persisted form has a single definition and can grow a richer payload later without touching its
 * callers. The current form is the bare state name — the actual state carries no payload beyond the
 * state itself, since progress offsets live in the source-read store, not the checkpoint.
 */
public final class StateJson {

    private StateJson() {
    }

    /** The wire form of a pipeline state, ready to hand to a checkpoint write. */
    public static String of(PipelineState state) {
        return state.name();
    }

    /**
     * Recovers the pipeline state from its wire form — the inverse of {@link #of}, used by the monitoring
     * read side to read the actual state back. The form is written only by {@link #of}, so an unrecognized
     * wire string is an invariant violation (a corrupt or newer-grammar checkpoint), left to crash rather
     * than laundered into a fabricated state.
     */
    public static PipelineState parse(String stateJson) {
        return PipelineState.valueOf(stateJson);
    }
}
