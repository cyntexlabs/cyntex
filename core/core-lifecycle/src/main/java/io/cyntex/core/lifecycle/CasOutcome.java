package io.cyntex.core.lifecycle;

/**
 * The result of a compare-and-swap against a checkpoint: either the write applied (carrying the new
 * doc) or the writer was fenced by a newer epoch (carrying the stored epoch that beat it). Modelled
 * as a value, not an exception, because losing a CAS race is an ordinary runtime outcome the caller
 * decides how to handle — not a diagnosable user error.
 */
public sealed interface CasOutcome permits CasOutcome.Applied, CasOutcome.Fenced {

    /** The swap succeeded; {@code next} is the new checkpoint doc, with the epoch bumped by one. */
    record Applied(CheckpointDoc next) implements CasOutcome {
    }

    /** The swap was rejected; {@code currentEpoch} is the stored epoch the stale writer was fenced by. */
    record Fenced(long currentEpoch) implements CasOutcome {
    }
}
