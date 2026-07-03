package io.cyntex.app;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code boot} domain's error codes: startup-fatal failures of the assembly root that are the
 * operator's to fix. Bringing the embedded Hazelcast member up can fail because its loopback port is
 * already held by another server on the same host — a user-facing, diagnosable failure carried
 * through the error-code system and rendered through the shared message catalog, not a bare stack.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and the build-time placeholder gate checks the catalog templates against it. This code
 * carries none on purpose — the underlying cause is attached to the exception (and logged), so the
 * operator-facing message stays a stable, detail-free diagnostic.
 */
enum BootError implements CyntexErrorCode {

    /** The embedded Hazelcast member could not be started (e.g. its loopback port is in use). */
    HAZELCAST_UNAVAILABLE("boot.hazelcast-unavailable", Set.of());

    private final String code;
    private final Set<String> placeholders;

    BootError(String code, Set<String> placeholders) {
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
