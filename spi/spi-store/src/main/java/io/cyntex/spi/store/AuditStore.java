package io.cyntex.spi.store;

/**
 * The audit-log port: an append-only sink for control-plane audit records. A pure interface over the
 * core ring only (rule R2); a store backend (a database adapter) implements it.
 *
 * <p>The port only persists a record; the "no audit, no execute" rule lives in the control layer's
 * audit gate, which writes a record through here before an audited operation runs and refuses the
 * operation if the write fails. {@link #record} is therefore expected to fail loudly — it throws
 * rather than swallowing a write failure — so the gate can enforce that rule.
 */
public interface AuditStore {

    /**
     * Appends one audit record. Append-only: an audit log is never updated or overwritten in place.
     * Throws if the record could not be durably written, so a caller enforcing "no audit, no execute"
     * can refuse the operation.
     */
    void record(AuditRecord record);
}
