package io.cyntex.control.core;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.dsl.DslException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.dsl.Workspace;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The resource-type-agnostic apply front half: validate -> canonical -> hash. Parses each draft
 * (structural + expression checks), validates the whole batch as one closure (duplicate ids,
 * reference closure, mode rules, and the connector capability matrix against the catalog), then
 * emits each resource's canonical form and content hash. It performs no store access — the upsert
 * (by id, hash-unchanged = no-op) is the caller's next step.
 *
 * <p>Any validation failure aborts the plan with the first coded {@code dsl.*} diagnostic; nothing
 * is prepared for upsert. The batch is the closure: references resolve within the submitted set. The
 * union with store-resident artifacts is layered in where the store is consulted, not here.
 */
public final class ApplyService {

    private final CyntexCatalog catalog;
    private final DslParser parser = new DslParser();
    private final CanonicalWriter writer = new CanonicalWriter();

    public ApplyService(CyntexCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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
        Workspace workspace = Workspace.of(resources, catalog);
        List<PreparedArtifact> prepared = new ArrayList<>();
        for (Resource resource : workspace.resources()) {
            String canonicalForm = writer.write(resource);
            prepared.add(new PreparedArtifact(resource, canonicalForm, CanonicalHash.of(canonicalForm)));
        }
        return new ApplyPlan(prepared);
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
