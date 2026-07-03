package io.cyntex.adapters.mongostore;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code store} domain's error codes: reaching the backing store at startup. These are
 * user-facing, diagnosable failures — the operator pointed the server at a store that is not
 * reachable, or at a standalone server when a replica-set is required (the checkpoint
 * compare-and-swap runs inside a multi-document transaction, which needs a replica-set).
 *
 * <p>Driver exceptions are translated into these coded diagnostics inside this module, so no
 * driver type escapes it (rule R3). {@code placeholders()} is the named-argument contract: every
 * throw site supplies a value for each name, and the build-time placeholder gate checks the
 * catalog templates against it.
 */
public enum StoreError implements CyntexErrorCode {

    /** The store could not be reached: {@code target} is the connection target that failed. */
    UNREACHABLE("store.unreachable", Set.of("target")),

    /** The store was reached but is not a replica-set: {@code target} is the connection target. */
    NOT_REPLICA_SET("store.not-replica-set", Set.of("target")),

    /**
     * The configured connection string is not a valid Mongo URI. Carries no placeholder on purpose:
     * echoing the raw URI back could leak an embedded credential.
     */
    INVALID_URI("store.invalid-uri", Set.of());

    private final String code;
    private final Set<String> placeholders;

    StoreError(String code, Set<String> placeholders) {
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
