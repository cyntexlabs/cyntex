package io.cyntex.control.core;

/**
 * Capability grade of a control operation, and the matching grade a credential must carry to invoke it.
 *
 * <p>Declaration order is the privilege order {@code READ < WRITE < ADMIN}: a credential of a given grade
 * may invoke operations at its grade or lower. There is exactly one grade per operation; per-frontend
 * openness is a separate axis carried by {@link Operation#exposure()}.
 */
public enum Scope {
    READ,
    WRITE,
    ADMIN
}
