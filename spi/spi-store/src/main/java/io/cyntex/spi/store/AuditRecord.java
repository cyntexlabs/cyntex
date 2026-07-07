package io.cyntex.spi.store;

import java.time.Instant;

/**
 * One entry in the control-plane audit log: the record written before an audited operation runs.
 *
 * <p>Fields — {@code timestamp} (when the operation was attempted), {@code principal} (the subject
 * that invoked it: a user id or a token id), {@code operationId} (the registry id of the operation,
 * e.g. {@code artifact.apply}), and {@code resourceId} (the target the operation acts on). These are
 * the fields knowable before the operation runs, which is what the audit-before-execute guarantee
 * needs. Richer fields the mature record carries — the revision transition of an artifact write, the
 * originating face, the outcome — attach where their determining step lands and are not modelled here.
 *
 * <p>A pure value over {@code java..} only (rule R2): the port stays free of any face or store type.
 */
public record AuditRecord(Instant timestamp, String principal, String operationId, String resourceId) {

    public AuditRecord {
        if (timestamp == null) {
            throw new IllegalArgumentException("audit record timestamp must be set");
        }
        requireText(principal, "principal");
        requireText(operationId, "operationId");
        requireText(resourceId, "resourceId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("audit record " + field + " must be non-blank");
        }
    }
}
