package io.cyntex.control.core;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code control} domain's error codes. These are user-facing, diagnosable failures of the
 * control layer, carried through the error-code system and rendered through the shared message
 * catalog.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and the build-time placeholder gate checks the catalog templates against it.
 */
public enum ControlError implements CyntexErrorCode {

    /**
     * An audited operation was refused because its mandatory audit record could not be written first;
     * {@code op} is the operation id. No audit, no execute — the operation never ran.
     */
    AUDIT_BLOCKED("control.audit-blocked", Set.of("op")),

    /**
     * A login was rejected: the username does not exist, or the password did not match. One code
     * covers both cases and it carries no placeholder on purpose — echoing nothing back means the
     * failure cannot be used to tell an existing username from an absent one (no user enumeration).
     */
    AUTH_FAILED("control.auth-failed", Set.of());

    private final String code;
    private final Set<String> placeholders;

    ControlError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
