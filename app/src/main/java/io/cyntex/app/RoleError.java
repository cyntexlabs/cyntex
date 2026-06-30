package io.cyntex.app;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code role} domain's error codes: the assembly root's process-role selection. The single
 * binary can run a subset of planes selected by {@code --role}; at L1 the planes are not separable,
 * so the only supported role is {@code all}. Any other value is a user-facing, diagnosable error
 * carried through the error-code system and rendered through the shared message catalog.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and the build-time placeholder gate checks the catalog templates against it.
 */
enum RoleError implements CyntexErrorCode {

    /** A {@code --role} value other than the L1-supported {@code all}. */
    UNSUPPORTED("role.unsupported", Set.of("role"));

    private final String code;
    private final Set<String> placeholders;

    RoleError(String code, Set<String> placeholders) {
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
