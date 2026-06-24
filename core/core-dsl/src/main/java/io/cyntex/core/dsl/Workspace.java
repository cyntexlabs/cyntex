package io.cyntex.core.dsl;

import io.cyntex.core.model.Resource;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A validated batch of parsed resources sharing one top-level id namespace (plan poc1 B3-5/B3-6).
 * Offline, the closure is the batch (ADR-0021 §3): every reference resolves within it. Building a
 * workspace enforces two batch-level invariants — the F8 / §2 id rule (top-level ids unique across
 * the batch) and reference closure ({@link ReferenceClosure}: source / from / use / sink references
 * resolve, X17 composition holds). Directory loading with per-file attribution is {@link WorkspaceLoader}.
 */
public final class Workspace {

    private final Map<String, Resource> byId;

    private Workspace(Map<String, Resource> byId) {
        this.byId = byId;
    }

    /**
     * Builds a workspace from already-parsed resources: rejects duplicate top-level ids
     * (F8: kind resources and pipelines share one namespace and must be unique), then validates
     * batch reference closure ({@link ReferenceClosure}). Returns only fully-valid batches; the
     * first violation throws a {@link DslException}.
     */
    public static Workspace of(List<Resource> resources) {
        Map<String, Resource> byId = new LinkedHashMap<>();
        for (Resource r : resources) {
            Resource clash = byId.putIfAbsent(r.id(), r);
            if (clash != null) {
                throw new DslException(DslError.DUPLICATE_ID, "id", 0, 0, null, Map.of("id", r.id()));
            }
        }
        ReferenceClosure.validate(byId.values());
        ModeRules.validate(byId.values());
        return new Workspace(byId);
    }

    /** The resource with the given top-level id, or {@code null} if absent. */
    public Resource resource(String id) {
        return byId.get(id);
    }

    /** All resources in the batch, in insertion order. */
    public Collection<Resource> resources() {
        return Collections.unmodifiableCollection(byId.values());
    }
}
