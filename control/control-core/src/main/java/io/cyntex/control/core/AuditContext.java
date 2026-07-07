package io.cyntex.control.core;

/**
 * The audit facts an invoker supplies for one operation: {@code principal} (the authenticated subject
 * — a user id or a token id) and {@code resourceId} (the target the operation acts on). The audit
 * gate combines these with the operation's own id and the current time to form the audit record, so
 * the caller supplies only what the operation's own metadata cannot.
 */
public record AuditContext(String principal, String resourceId) {

    public AuditContext {
        requireText(principal, "principal");
        requireText(resourceId, "resourceId");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("audit context " + field + " must be non-blank");
        }
    }
}
