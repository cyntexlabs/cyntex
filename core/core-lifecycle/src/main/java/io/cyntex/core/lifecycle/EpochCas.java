package io.cyntex.core.lifecycle;

import java.time.Instant;

/**
 * Compare-and-swap on the checkpoint fencing epoch — the single legal way a transition lands in the
 * store. Pure function: it computes the next doc (or a fenced outcome) from the current doc and the
 * writer's expectation; the real conditional Mongo update that executes it atomically lives in an
 * adapter, never here.
 *
 * <p>A swap succeeds if and only if the writer's {@code expectedEpoch} equals the stored epoch. On
 * success the epoch increments by one, the state swaps, and the touch time refreshes. Any other
 * expectation — always a stale one, since the epoch only ever rises and a writer can only have seen
 * a value at or below the current — is fenced: the store is not touched and the caller learns it has
 * been superseded. This is what prevents two owners that both believe they hold the pipeline from
 * both writing: only one CAS at a given epoch can win, and it fences the other.
 */
public final class EpochCas {

    private EpochCas() {
    }

    /**
     * Attempts to swap {@code current}'s state for {@code nextStateJson} under the writer's
     * {@code expectedEpoch}. Returns {@link CasOutcome.Applied} with the epoch-bumped doc on a
     * match, or {@link CasOutcome.Fenced} carrying the stored epoch otherwise.
     */
    public static CasOutcome swap(CheckpointDoc current, long expectedEpoch, String nextStateJson, Instant touchTime) {
        if (current.epoch() != expectedEpoch) {
            return new CasOutcome.Fenced(current.epoch());
        }
        return new CasOutcome.Applied(
                new CheckpointDoc(current.pipelineId(), nextStateJson, current.epoch() + 1, touchTime));
    }
}
