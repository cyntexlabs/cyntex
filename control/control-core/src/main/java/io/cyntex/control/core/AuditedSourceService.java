package io.cyntex.control.core;

import java.util.List;
import java.util.Objects;

/** Applies mandatory audit-first dispatch to Source writes while leaving reads unaudited. */
public final class AuditedSourceService {

    private final SourceService sourceService;
    private final AuditGate auditGate;

    public AuditedSourceService(SourceService sourceService, AuditGate auditGate) {
        this.sourceService = Objects.requireNonNull(sourceService, "sourceService");
        this.auditGate = Objects.requireNonNull(auditGate, "auditGate");
    }

    /** Lists Sources without writing an audit record. */
    public List<SourceView> list() {
        return sourceService.list();
    }

    /** Gets one Source without writing an audit record. */
    public SourceView get(String id) {
        return sourceService.get(id);
    }

    /** Creates one Source only after its audit record is stored. */
    public SourceView create(String principal, SourceDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return auditGate.dispatch(
                ControlOperations.SOURCE_CREATE,
                new AuditContext(principal, draft.id()),
                () -> sourceService.create(draft));
    }

    /** Replaces one Source only after its audit record is stored. */
    public SourceView replace(
            String principal, String id, String expectedContentHash, SourceDraft draft) {
        return auditGate.dispatch(
                ControlOperations.SOURCE_UPDATE,
                new AuditContext(principal, id),
                () -> sourceService.replace(id, expectedContentHash, draft));
    }

    /** Deletes one Source only after its audit record is stored. */
    public void delete(String principal, String id, String expectedContentHash) {
        auditGate.dispatch(
                ControlOperations.SOURCE_DELETE,
                new AuditContext(principal, id),
                () -> {
                    sourceService.delete(id, expectedContentHash);
                    return null;
                });
    }
}
