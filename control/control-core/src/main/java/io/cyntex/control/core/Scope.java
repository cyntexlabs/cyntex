package io.cyntex.control.core;

import java.util.Objects;

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
    ADMIN;

    /**
     * Whether a credential of this grade may invoke an operation requiring {@code required}: true when
     * this grade is at least as high as the required one in the privilege order {@code READ < WRITE <
     * ADMIN}. So {@code ADMIN} permits every grade and {@code READ} permits only {@code READ}. This is
     * the single place the grade ordering is decided; the authorization check builds on it.
     */
    public boolean permits(Scope required) {
        Objects.requireNonNull(required, "required");
        return ordinal() >= required.ordinal();
    }
}
