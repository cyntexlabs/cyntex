package io.cyntex.control.core;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code monitor} domain's error codes: user-facing, diagnosable failures of a store-backed
 * observation read — a status / metrics / snapshot read of a pipeline whose observation document has
 * not been published — carried through the error-code system and rendered through the shared message
 * catalog. The read also serves frontends with no stderr/exit channel, so a missing observation is a
 * coded diagnostic, not a bare usage error.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and the build-time placeholder gate checks the catalog templates against it.
 */
public enum MonitorError implements CyntexErrorCode {

    /**
     * A status / metrics / snapshot read named a pipeline that has published no observation:
     * {@code pipeline} is the id the caller asked to observe.
     */
    NO_OBSERVATION("monitor.no-observation", Set.of("pipeline"));

    private final String code;
    private final Set<String> placeholders;

    MonitorError(String code, Set<String> placeholders) {
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
