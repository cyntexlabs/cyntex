package io.cyntex.control.core;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The "no audit, no execute" gate every audited operation passes through. An operation the registry
 * marks {@code audited} (a write / admin verb that mutates persisted control-plane state) must leave
 * an audit record before it runs: the gate writes the record first, and if that write fails the
 * operation is refused — it never runs — with a coded {@code control.audit-blocked} diagnostic that
 * carries the store failure as its cause. An operation with no audit flag (a read / list / probe)
 * runs straight through and leaves no record.
 *
 * <p>This is the audit-first form of the guarantee: the record is written before the action, so a
 * failed audit provably precedes any effect. The stronger single-transaction form — where an
 * artifact write and its audit record commit together — attaches where the artifact write itself
 * lands; either form upholds the same invariant that an unaudited operation does not execute.
 */
public final class AuditGate {

    private final AuditStore auditStore;
    private final Clock clock;

    public AuditGate(AuditStore auditStore, Clock clock) {
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs {@code action} under the audit gate. When {@code op} is audited, an audit record is written
     * first; a write failure refuses the operation ({@code control.audit-blocked}) and {@code action}
     * never runs. When {@code op} is not audited, {@code action} runs directly with no record.
     */
    public <T> T dispatch(Operation op, AuditContext ctx, Supplier<T> action) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(action, "action");
        if (op.audited()) {
            AuditRecord record = new AuditRecord(clock.instant(), ctx.principal(), op.id(), ctx.resourceId());
            try {
                auditStore.record(record);
            } catch (RuntimeException cause) {
                throw new CyntexException(ControlError.AUDIT_BLOCKED, Map.of("op", op.id()), cause);
            }
        }
        return action.get();
    }
}
