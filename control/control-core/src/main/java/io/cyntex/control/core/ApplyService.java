package io.cyntex.control.core;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.dsl.DslException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.dsl.Workspace;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The resource-type-agnostic apply pipeline. {@link #plan} is the front half — validate -> canonical
 * -> hash: it parses each draft (structural + expression checks), validates the whole batch as one
 * closure (duplicate ids, reference closure, mode rules, and the connector capability matrix against
 * the catalog), then emits each resource's canonical form and content hash, touching no store.
 * {@link #apply} runs a plan and then upserts each artifact into the store by its id, skipping the
 * write when the stored artifact's content hash is unchanged (a no-op).
 *
 * <p>Any validation failure aborts with the first coded {@code dsl.*} diagnostic before any upsert;
 * nothing is written on a validation failure. The batch is the closure: references resolve within the
 * submitted set. The union with store-resident artifacts is layered in where the store is consulted.
 *
 * <p>The catalog is supplied per plan rather than fixed, so the online path validates against the live
 * catalog view — the bundled snapshot union the connectors registered so far — and a connector
 * registered at runtime is honoured without a restart.
 *
 * <p>The no-op is keyed by the content hash over the canonical form, so re-applying identical content
 * — even with different raw key order — writes nothing. Apply writes the changed set — the created and
 * updated artifacts — as one atomic batch, so a mid-batch write failure rolls the whole batch back and
 * no partial batch is stored, matching the validation-failure guarantee on the write side.
 */
public final class ApplyService {

    private final Supplier<CyntexCatalog> catalog;
    private final ArtifactStore store;
    private final DslParser parser = new DslParser();
    private final CanonicalWriter writer = new CanonicalWriter();

    public ApplyService(Supplier<CyntexCatalog> catalog, ArtifactStore store) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Validates and canonicalizes {@code drafts} as one batch, returning the artifacts an apply
     * would upsert. Throws the first {@link DslException} (a coded, user-facing diagnostic) on any
     * structural / reference / mode / capability violation.
     */
    public ApplyPlan plan(List<ArtifactDraft> drafts) {
        Objects.requireNonNull(drafts, "drafts");
        List<Resource> resources = new ArrayList<>();
        for (ArtifactDraft draft : drafts) {
            resources.add(parse(draft));
        }
        Workspace workspace = Workspace.of(resources, catalog.get());
        List<PreparedArtifact> prepared = new ArrayList<>();
        for (Resource resource : workspace.resources()) {
            String canonicalForm = writer.write(resource);
            prepared.add(new PreparedArtifact(resource, canonicalForm, CanonicalHash.of(canonicalForm)));
        }
        return new ApplyPlan(prepared);
    }

    /**
     * Validates the batch (via {@link #plan}), then writes the changed set — created and updated
     * artifacts — into the store as one atomic batch, returning one outcome per artifact in submission
     * order. An artifact whose stored content hash already equals the applied one is a no-op and is left
     * out of the batch. A validation failure throws the first {@link DslException} before any write, and
     * a store write failure rolls the whole batch back, so nothing is stored on an invalid or a failed
     * batch.
     */
    public ApplyResult apply(List<ArtifactDraft> drafts) {
        ApplyPlan plan = plan(drafts);
        List<ArtifactOutcome> outcomes = new ArrayList<>();
        List<Resource> toWrite = new ArrayList<>();
        for (PreparedArtifact prepared : plan.artifacts()) {
            Optional<Resource> existing = store.get(prepared.id());
            ArtifactOutcome.Change change;
            if (existing.isEmpty()) {
                change = ArtifactOutcome.Change.CREATED;
                toWrite.add(prepared.resource());
            } else if (storedHash(existing.get()).equals(prepared.contentHash())) {
                change = ArtifactOutcome.Change.UNCHANGED;
            } else {
                change = ArtifactOutcome.Change.UPDATED;
                toWrite.add(prepared.resource());
            }
            outcomes.add(new ArtifactOutcome(prepared.id(), prepared.kind(), change, prepared.contentHash()));
        }
        // One atomic batch for the whole changed set: all of it lands or, on a write failure, none does.
        store.saveAll(toWrite);
        return new ApplyResult(outcomes);
    }

    /** The content hash of a stored artifact, recomputed over its canonical form for the no-op check. */
    private String storedHash(Resource stored) {
        return CanonicalHash.of(writer.write(stored));
    }

    private Resource parse(ArtifactDraft draft) {
        try {
            return parser.parse(draft.content());
        } catch (DslException e) {
            // A parse error is located at exactly this draft; attribute it when the origin is known.
            throw draft.source() != null ? e.withSource(draft.source()) : e;
        }
    }
}
