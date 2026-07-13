package io.cyntex.control.core;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.dsl.DslError;
import io.cyntex.core.model.Metadata;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactMutation;
import io.cyntex.spi.store.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceServiceTest {

    private final CyntexCatalog catalog = CyntexCatalog.load();
    private final InMemoryArtifactStore store = new InMemoryArtifactStore();
    private final SourceService service =
            new SourceService(catalog, store, new SourceRepresentation(catalog));

    @Test
    void createIsCreateOnlyAndReturnsTheCanonicalContentHash() {
        SourceView created = service.create(draft("orders", "snapshot", "before"));

        assertThat(created.id()).isEqualTo("orders");
        assertThat(created.metadata().description()).isEqualTo("before");
        assertThat(created.contentHash()).matches("[0-9a-f]{64}");
        assertThat(service.get("orders")).isEqualTo(created);

        assertSourceError(
                () -> service.create(draft("orders", "snapshot", "duplicate")),
                SourceError.ALREADY_EXISTS,
                Map.of("id", "orders"));
        assertThat(service.get("orders")).isEqualTo(created);
    }

    @Test
    void listReturnsOnlySourcesSortedByIdAndGetRejectsMissingOrOtherKinds() {
        store.save(source("zeta", "zeta"));
        store.save(pipeline("pipeline", "zeta"));
        store.save(source("alpha", "alpha"));

        assertThat(service.list()).extracting(SourceView::id)
                .containsExactly("alpha", "zeta");
        assertThat(service.get("zeta").metadata().description()).isEqualTo("zeta");
        assertSourceError(
                () -> service.get("missing"),
                SourceError.NOT_FOUND,
                Map.of("id", "missing"));
        assertSourceError(
                () -> service.get("pipeline"),
                SourceError.NOT_FOUND,
                Map.of("id", "pipeline"));
    }

    @Test
    void replaceIsUpdateOnlyAndRequiresMatchingImmutableId() {
        SourceDraft body = draft("orders", "snapshot", "after");

        assertSourceError(
                () -> service.replace("orders", "0".repeat(64), body),
                SourceError.NOT_FOUND,
                Map.of("id", "orders"));

        SourceView created = service.create(draft("orders", "snapshot", "before"));
        SourceDraft wrongId = draft("customers", "snapshot", "after");
        assertSourceError(
                () -> service.replace("orders", created.contentHash(), wrongId),
                SourceError.ID_MISMATCH,
                Map.of("pathId", "orders", "bodyId", "customers"));
        assertThat(service.get("orders").metadata().description()).isEqualTo("before");
    }

    @Test
    void replaceRequiresAPreconditionAndRejectsAStaleVersionWithoutMutation() {
        SourceView created = service.create(draft("orders", "snapshot", "before"));
        SourceDraft changed = draft("orders", "snapshot", "after");

        assertSourceError(
                () -> service.replace("orders", null, changed),
                SourceError.PRECONDITION_REQUIRED,
                Map.of("id", "orders"));
        assertSourceError(
                () -> service.replace("orders", "0".repeat(64), changed),
                SourceError.VERSION_CONFLICT,
                Map.of("id", "orders"));
        assertThat(service.get("orders")).isEqualTo(created);
    }

    @Test
    void successfulReplaceReturnsTheNewCanonicalVersion() {
        SourceView created = service.create(draft("orders", "snapshot", "before"));

        SourceView replaced = service.replace(
                "orders", created.contentHash(), draft("orders", "snapshot", "after"));

        assertThat(replaced.metadata().description()).isEqualTo("after");
        assertThat(replaced.contentHash()).matches("[0-9a-f]{64}");
        assertThat(replaced.contentHash()).isNotEqualTo(created.contentHash());
        assertThat(service.get("orders")).isEqualTo(replaced);
    }

    @Test
    void createAndReplaceValidateTheFullCandidateStoredClosureBeforeMutation() {
        store.save(validShapePipeline("dangling", "missing"));

        assertThatThrownBy(() -> service.create(draft("orders", "snapshot", "new")))
                .isInstanceOfSatisfying(CyntexException.class,
                        error -> assertThat(error.code()).isEqualTo(DslError.MISSING_REFERENCE));
        assertThat(store.get("orders")).isEmpty();

        store.save(source("orders", "before"));
        String beforeHash = hash(store.get("orders").orElseThrow());
        assertThatThrownBy(() -> service.replace(
                "orders", beforeHash, draft("orders", "snapshot", "after")))
                .isInstanceOfSatisfying(CyntexException.class,
                        error -> assertThat(error.code()).isEqualTo(DslError.MISSING_REFERENCE));
        assertThat(hash(store.get("orders").orElseThrow())).isEqualTo(beforeHash);
    }

    @Test
    void invalidConnectorModeAndCapabilityLeaveTheStoreUnchanged() {
        assertThatThrownBy(() -> service.create(draft("unknown", "missing", "snapshot", Map.of())))
                .isInstanceOfSatisfying(CyntexException.class,
                        error -> assertThat(error.code()).isEqualTo(ControlError.MALFORMED_REQUEST));
        assertThatThrownBy(() -> service.create(draft("bad-mode", "mysql", "file", validConfig())))
                .isInstanceOfSatisfying(CyntexException.class,
                        error -> assertThat(error.code()).isEqualTo(DslError.UNSUPPORTED_MODE));
        assertThatThrownBy(() -> service.create(draft(
                "bad-config", "mysql", "snapshot", Map.of("port", Map.of("nested", true)))))
                .isInstanceOfSatisfying(CyntexException.class,
                        error -> assertThat(error.code()).isEqualTo(DslError.CONFIG_TYPE_MISMATCH));
        assertThat(store.list()).isEmpty();

        SourceView created = service.create(draft("orders", "snapshot", "before"));
        assertThatThrownBy(() -> service.replace(
                "orders",
                created.contentHash(),
                draft("orders", "mysql", "api", validConfig())))
                .isInstanceOfSatisfying(CyntexException.class,
                        error -> assertThat(error.code()).isEqualTo(DslError.UNSUPPORTED_MODE));
        assertThat(service.get("orders")).isEqualTo(created);
    }

    @Test
    void deleteRequiresAPreconditionAndRejectsStaleVersionsWithoutMutation() {
        SourceView created = service.create(draft("orders", "snapshot", "before"));

        assertSourceError(
                () -> service.delete("orders", null),
                SourceError.PRECONDITION_REQUIRED,
                Map.of("id", "orders"));
        assertSourceError(
                () -> service.delete("orders", "0".repeat(64)),
                SourceError.VERSION_CONFLICT,
                Map.of("id", "orders"));
        assertThat(service.get("orders")).isEqualTo(created);
    }

    @Test
    void referencedDeleteIsRefusedWithSortedReferrerIdsAndNoCascade() {
        SourceView created = service.create(draft("orders", "snapshot", "before"));
        store.save(pipeline("zeta", "orders"));
        store.save(pipeline("alpha", "orders"));

        assertSourceError(
                () -> service.delete("orders", created.contentHash()),
                SourceError.IN_USE,
                Map.of("id", "orders", "referrers", List.of("alpha", "zeta")));
        assertThat(store.get("orders")).isPresent();
        assertThat(store.get("alpha")).isPresent();
        assertThat(store.get("zeta")).isPresent();
    }

    @Test
    void unreferencedDeleteIsUpdateOnlyAndRemovesOnlyTheSource() {
        store.save(pipeline("unrelated", "elsewhere"));
        store.save(source("orders", "before"));
        String hash = hash(store.get("orders").orElseThrow());

        service.delete("orders", hash);

        assertThat(store.get("orders")).isEmpty();
        assertThat(store.get("unrelated")).isPresent();
        assertSourceError(
                () -> service.delete("orders", hash),
                SourceError.NOT_FOUND,
                Map.of("id", "orders"));
    }

    private static SourceDraft draft(String id, String mode, String description) {
        return draft(id, "mysql", mode, validConfig(), description);
    }

    private static SourceDraft draft(
            String id, String connector, String mode, Map<String, Object> config) {
        return draft(id, connector, mode, config, "draft");
    }

    private static SourceDraft draft(
            String id,
            String connector,
            String mode,
            Map<String, Object> config,
            String description) {
        return new SourceDraft(
                id,
                new Metadata(Map.of("team", "data"), description),
                connector,
                config,
                mode,
                List.of(new SourceTableDraft("literal", "orders", null, null, null, null)),
                Map.of(),
                null,
                Map.of(),
                List.of());
    }

    private static Map<String, Object> validConfig() {
        return Map.of("host", "localhost", "port", "3306");
    }

    private static SourceResource source(String id, String description) {
        CyntexCatalog catalog = CyntexCatalog.load();
        return new SourceRepresentation(catalog).toModel(
                draft(id, "snapshot", description), null);
    }

    private static PipelineResource pipeline(String id, String sourceId) {
        return new PipelineResource(id, null, List.of(sourceId), null, null, null, null, null);
    }

    private static PipelineResource validShapePipeline(String id, String sourceId) {
        String yaml = """
                version: cyntex/v1
                kind: pipeline
                id: %s
                source: %s
                transforms:
                  - id: selected
                    type: filter
                    from: [orders]
                    expr: "op != 'd'"
                view:
                  id: orders_view
                  from: selected
                  primary_key: id
                  storage:
                    warm:
                      collection: orders_view
                """.formatted(id, sourceId);
        return (PipelineResource) new DslParser().parse(yaml);
    }

    private static String hash(Resource resource) {
        return CanonicalHash.of(new CanonicalWriter().write(resource));
    }

    private static void assertSourceError(
            ThrowingAction action, SourceError code, Map<String, Object> args) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CyntexException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.args()).containsExactlyInAnyOrderEntriesOf(args);
                });
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    private static final class InMemoryArtifactStore implements ArtifactStore {

        private final Map<String, Resource> artifacts = new LinkedHashMap<>();

        @Override
        public synchronized ArtifactMutation create(Resource artifact) {
            if (artifacts.containsKey(artifact.id())) {
                return ArtifactMutation.ALREADY_EXISTS;
            }
            artifacts.put(artifact.id(), artifact);
            return ArtifactMutation.CREATED;
        }

        @Override
        public synchronized ArtifactMutation replace(
                String id, String expectedContentHash, Resource replacement) {
            Resource existing = artifacts.get(id);
            if (existing == null) {
                return ArtifactMutation.NOT_FOUND;
            }
            if (!hash(existing).equals(expectedContentHash)) {
                return ArtifactMutation.VERSION_CONFLICT;
            }
            artifacts.put(id, replacement);
            return ArtifactMutation.REPLACED;
        }

        @Override
        public synchronized ArtifactMutation delete(String id, String expectedContentHash) {
            Resource existing = artifacts.get(id);
            if (existing == null) {
                return ArtifactMutation.NOT_FOUND;
            }
            if (!hash(existing).equals(expectedContentHash)) {
                return ArtifactMutation.VERSION_CONFLICT;
            }
            artifacts.remove(id);
            return ArtifactMutation.DELETED;
        }

        @Override
        public synchronized void saveAll(List<Resource> resources) {
            resources.forEach(resource -> artifacts.put(resource.id(), resource));
        }

        @Override
        public synchronized Optional<Resource> get(String id) {
            return Optional.ofNullable(artifacts.get(id));
        }

        @Override
        public synchronized List<Resource> list() {
            return new ArrayList<>(artifacts.values());
        }
    }
}
