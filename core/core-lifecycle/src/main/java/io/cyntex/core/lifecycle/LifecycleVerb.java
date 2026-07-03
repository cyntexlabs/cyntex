package io.cyntex.core.lifecycle;

import java.util.Locale;

/**
 * The four user-driven lifecycle verbs. {@code start} re-snapshots (only from a clean origin),
 * {@code pause} suspends while retaining offset / state, {@code resume} continues a paused pipeline
 * from where it left off, and {@code stop} halts and clears offset / state. There is deliberately no
 * {@code restart} or {@code reset}: "re-snapshot" is the explicit two-step {@code stop} then
 * {@code start}, and "continue" is {@code resume}.
 */
public enum LifecycleVerb {

    START,
    PAUSE,
    RESUME,
    STOP;

    /** The canonical lower-case verb name used in user-facing messages and command surfaces. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
