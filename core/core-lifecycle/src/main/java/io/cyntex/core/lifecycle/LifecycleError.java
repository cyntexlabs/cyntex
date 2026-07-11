package io.cyntex.core.lifecycle;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code lifecycle} domain's error codes: user-facing, diagnosable failures of a pipeline's
 * lifecycle — a transition the state machine forbids (e.g. {@code start} on a paused pipeline), or a
 * {@code start}/{@code resume} refused because the pipeline's revision is not the latest applied one —
 * carried through the error-code system and rendered through the shared message catalog.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and the build-time placeholder gate checks the catalog templates against it.
 */
public enum LifecycleError implements CyntexErrorCode {

    /** A verb rejected from the current state: {@code from} is the state, {@code verb} the attempted action. */
    ILLEGAL_TRANSITION("lifecycle.illegal-transition", Set.of("from", "verb")),

    /**
     * A {@code start}/{@code resume} refused because the pipeline's revision is not the latest applied
     * one: {@code requested} is the revision the action would run at, {@code latest} the newest applied.
     */
    INCOMPATIBLE_REVISION("lifecycle.incompatible-revision", Set.of("requested", "latest"));

    private final String code;
    private final Set<String> placeholders;

    LifecycleError(String code, Set<String> placeholders) {
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
