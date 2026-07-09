package io.cyntex.runtime.probe;

/**
 * The outcome of a connection probe. The first landing only ever produces
 * {@link Outcome#NOT_IMPLEMENTED}; the reachable / unreachable outcomes are produced by the real
 * probe once the connector plane wires it.
 */
public record ProbeVerdict(Outcome outcome, String detail) {

    public ProbeVerdict {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must be present");
        }
        detail = detail == null ? "" : detail;
    }

    /** The verdict the first-landing stub returns: the probe is reserved, not yet implemented. */
    public static ProbeVerdict notImplemented(String detail) {
        return new ProbeVerdict(Outcome.NOT_IMPLEMENTED, detail);
    }

    /** The possible outcomes of a probe. */
    public enum Outcome {

        /** The probe is reserved but its logic has not landed yet (the first-landing stub). */
        NOT_IMPLEMENTED,

        /** The target connection was opened successfully (produced by the real probe). */
        REACHABLE,

        /** The target connection could not be opened (produced by the real probe). */
        UNREACHABLE
    }
}
