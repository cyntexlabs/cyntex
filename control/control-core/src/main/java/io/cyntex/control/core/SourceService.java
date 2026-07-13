package io.cyntex.control.core;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.dsl.ReferenceGraph;
import io.cyntex.core.dsl.Workspace;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactMutation;
import io.cyntex.spi.store.ArtifactStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source-specific CRUD over the canonical artifact truth layer. */
public final class SourceService {

    private final CyntexCatalog catalog;
    private final ArtifactStore store;
    private final SourceRepresentation representation;
    private final CanonicalWriter writer = new CanonicalWriter();

    public SourceService(
            CyntexCatalog catalog, ArtifactStore store, SourceRepresentation representation) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.store = Objects.requireNonNull(store, "store");
        this.representation = Objects.requireNonNull(representation, "representation");
    }

    /** Lists stored Sources ordered by id. */
    public List<SourceView> list() {
        return store.list().stream()
                .filter(SourceResource.class::isInstance)
                .map(SourceResource.class::cast)
                .sorted(Comparator.comparing(SourceResource::id))
                .map(this::view)
                .toList();
    }

    /** Returns one stored Source or a coded not-found failure. */
    public SourceView get(String id) {
        Objects.requireNonNull(id, "id");
        return view(storedSource(id));
    }

    /** Creates one Source only when its id is not already present. */
    public SourceView create(SourceDraft draft) {
        Objects.requireNonNull(draft, "draft");
        List<Resource> candidate = new ArrayList<>(store.list());
        if (candidate.stream().anyMatch(resource -> resource.id().equals(draft.id()))) {
            throw error(SourceError.ALREADY_EXISTS, Map.of("id", draft.id()));
        }

        SourceResource source = representation.toModel(draft, null);
        candidate.add(source);
        Workspace.of(candidate, catalog);

        return switch (store.create(source)) {
            case CREATED -> view(source);
            case ALREADY_EXISTS -> throw error(
                    SourceError.ALREADY_EXISTS, Map.of("id", source.id()));
            default -> throw unexpectedMutation("create");
        };
    }

    /** Replaces one existing Source when the supplied canonical content hash is current. */
    public SourceView replace(String id, String expectedContentHash, SourceDraft draft) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(draft, "draft");
        if (!id.equals(draft.id())) {
            throw error(
                    SourceError.ID_MISMATCH,
                    Map.of("pathId", id, "bodyId", draft.id()));
        }
        requirePrecondition(id, expectedContentHash);

        List<Resource> candidate = new ArrayList<>(store.list());
        SourceResource existing = candidate.stream()
                .filter(resource -> resource.id().equals(id))
                .filter(SourceResource.class::isInstance)
                .map(SourceResource.class::cast)
                .findFirst()
                .orElseThrow(() -> error(SourceError.NOT_FOUND, Map.of("id", id)));
        SourceResource replacement = representation.toModel(draft, existing);
        for (int index = 0; index < candidate.size(); index++) {
            if (candidate.get(index) == existing) {
                candidate.set(index, replacement);
                break;
            }
        }
        Workspace.of(candidate, catalog);

        return switch (store.replace(id, expectedContentHash, replacement)) {
            case REPLACED -> view(replacement);
            case NOT_FOUND -> throw error(SourceError.NOT_FOUND, Map.of("id", id));
            case VERSION_CONFLICT -> throw error(
                    SourceError.VERSION_CONFLICT, Map.of("id", id));
            default -> throw unexpectedMutation("replace");
        };
    }

    /** Deletes one unreferenced Source when the supplied canonical content hash is current. */
    public void delete(String id, String expectedContentHash) {
        Objects.requireNonNull(id, "id");
        requirePrecondition(id, expectedContentHash);

        List<Resource> stored = store.list();
        boolean sourceExists = stored.stream()
                .anyMatch(resource -> resource.id().equals(id) && resource instanceof SourceResource);
        if (!sourceExists) {
            throw error(SourceError.NOT_FOUND, Map.of("id", id));
        }
        List<String> referrers = ReferenceGraph.of(stored).referencedBy(id).stream()
                .map(ReferenceGraph.Edge::id)
                .sorted()
                .toList();
        if (!referrers.isEmpty()) {
            throw error(
                    SourceError.IN_USE,
                    Map.of("id", id, "referrers", referrers));
        }

        switch (store.delete(id, expectedContentHash)) {
            case DELETED -> {
            }
            case NOT_FOUND -> throw error(SourceError.NOT_FOUND, Map.of("id", id));
            case VERSION_CONFLICT -> throw error(
                    SourceError.VERSION_CONFLICT, Map.of("id", id));
            default -> throw unexpectedMutation("delete");
        }
    }

    private SourceResource storedSource(String id) {
        return store.get(id)
                .filter(SourceResource.class::isInstance)
                .map(SourceResource.class::cast)
                .orElseThrow(() -> error(SourceError.NOT_FOUND, Map.of("id", id)));
    }

    private SourceView view(SourceResource source) {
        String contentHash = CanonicalHash.of(writer.write(source));
        return representation.toView(source, contentHash);
    }

    private static void requirePrecondition(String id, String expectedContentHash) {
        if (expectedContentHash == null) {
            throw error(SourceError.PRECONDITION_REQUIRED, Map.of("id", id));
        }
    }

    private static CyntexException error(SourceError code, Map<String, Object> args) {
        return new CyntexException(code, args, null);
    }

    private static IllegalStateException unexpectedMutation(String operation) {
        return new IllegalStateException("unexpected artifact mutation outcome for " + operation);
    }
}
