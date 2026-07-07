package io.cyntex.control.core;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.dsl.DslError;
import io.cyntex.core.dsl.DslException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.spi.store.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The resource-type-agnostic apply pipeline. {@code plan} validates a batch (structural, reference
 * closure, connector capability matrix, batch duplicate id) and emits each resource's canonical form
 * and content hash, touching no store. {@code apply} runs a plan and then upserts each artifact by id
 * into the store, skipping the write when the stored artifact's content hash is unchanged — the
 * idempotency key is the hash over the canonical form, so re-applying unchanged content writes
 * nothing. A validation failure aborts before any write.
 */
class ApplyServiceTest {

    private final RecordingArtifactStore store = new RecordingArtifactStore();
    private final ApplyService service = new ApplyService(CyntexCatalog.load(), store);

    // A guaranteed-valid mysql source used as a pure connection (X18 dual-role: no mode / tables).
    private static final String TGT_MY = """
            version: cyntex/v1
            kind: source
            id: tgt_my
            connector: mysql
            config: { host: 10.30.0.5, username: writer, password: My_2026 }
            """;

    private static ArtifactDraft draft(String content) {
        return new ArtifactDraft(null, content);
    }

    // ---- the store-free front half: validate -> canonical -> hash ----

    @Test
    void planCanonicalizesAndHashesAValidResource() {
        ApplyPlan plan = service.plan(List.of(draft(TGT_MY)));

        assertThat(plan.artifacts()).hasSize(1);
        PreparedArtifact prepared = plan.artifacts().get(0);
        assertThat(prepared.id()).isEqualTo("tgt_my");
        assertThat(prepared.kind()).isEqualTo("source");
        String expectedCanonical = new CanonicalWriter().write(prepared.resource());
        assertThat(prepared.canonicalForm())
                .as("the canonical form is the deterministic serializer's output")
                .isEqualTo(expectedCanonical);
        assertThat(prepared.contentHash())
                .as("the content hash is taken over the canonical form")
                .isEqualTo(CanonicalHash.of(expectedCanonical));
    }

    @Test
    void anUnknownFieldIsRejectedAtValidationWithItsDslCode() {
        // The structural tier: a field outside the cyntex/v1 schema.
        String withUnknownField = TGT_MY + "bogus_field: 1\n";

        Throwable t = catchThrowable(() -> service.plan(List.of(draft(withUnknownField))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(((DslException) t).code()).isEqualTo(DslError.UNKNOWN_FIELD);
    }

    @Test
    void aMissingReferenceInTheBatchIsRejected() {
        // The reference-closure tier: a pipeline whose source is not in the batch.
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: mirror
                source: src_absent
                serve:
                  from: /.*/
                  sync:
                    - id: out
                      source: tgt_absent
                      write_mode: upsert
                """;

        Throwable t = catchThrowable(() -> service.plan(List.of(draft(pipeline))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(((DslException) t).code()).isEqualTo(DslError.MISSING_REFERENCE);
    }

    @Test
    void aCapabilityViolationIsRejected() {
        // The capability-matrix tier: kafka declares only [stream]; cdc is outside its matrix.
        String kafkaCdc = """
                version: cyntex/v1
                kind: source
                id: src_k
                connector: kafka
                config: { nameSrvAddr: "k1:9092" }
                mode: cdc
                tables: [ events ]
                """;

        Throwable t = catchThrowable(() -> service.plan(List.of(draft(kafkaCdc))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(((DslException) t).code()).isEqualTo(DslError.UNSUPPORTED_MODE);
    }

    @Test
    void anUnknownKindIsRejectedWithACodedDiagnostic() {
        // An illegal resource must surface a coded dsl.* diagnostic at validation, never an uncoded
        // exception that a caller (rest-api) would map to a 500 for a plain user typo.
        String unknownKind = """
                version: cyntex/v1
                kind: bogus
                id: x
                """;

        Throwable t = catchThrowable(() -> service.plan(List.of(draft(unknownKind))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(((DslException) t).code()).isEqualTo(DslError.ILLEGAL_VALUE);
    }

    @Test
    void aDuplicateIdAcrossTheBatchIsRejected() {
        Throwable t = catchThrowable(() -> service.plan(List.of(draft(TGT_MY), draft(TGT_MY))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(((DslException) t).code()).isEqualTo(DslError.DUPLICATE_ID);
    }

    @Test
    void aParseErrorIsAttributedToItsDraftSource() {
        String withUnknownField = TGT_MY + "bogus_field: 1\n";

        Throwable t = catchThrowable(() ->
                service.plan(List.of(new ArtifactDraft("tgt_my.cyn.yml", withUnknownField))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(((DslException) t).source())
                .as("a parse error is located at its originating draft")
                .isEqualTo("tgt_my.cyn.yml");
    }

    @Test
    void theContentHashIsOverTheCanonicalFormNotTheRawText() {
        // Same resource, different raw key order in config — canonical sorts free-map keys, so both
        // canonicalize identically and must hash identically (the idempotency key property).
        String reordered = """
                version: cyntex/v1
                kind: source
                id: tgt_my
                connector: mysql
                config: { password: My_2026, username: writer, host: 10.30.0.5 }
                """;

        String hashA = service.plan(List.of(draft(TGT_MY))).artifacts().get(0).contentHash();
        String hashB = service.plan(List.of(draft(reordered))).artifacts().get(0).contentHash();

        assertThat(hashA).isEqualTo(hashB);
    }

    @Test
    void aMultiResourceWorkspaceIsPreparedPerResource() {
        ApplyPlan plan = service.plan(List.of(draft(SRC_ORA), draft(PIPELINE), draft(TGT_MY)));

        assertThat(plan.artifacts()).extracting(PreparedArtifact::id)
                .containsExactly("src_ora", "ora2my_ods", "tgt_my");
        assertThat(plan.artifacts()).allSatisfy(a ->
                assertThat(a.contentHash()).matches("[0-9a-f]{64}"));
    }

    @Test
    void anEmptyBatchProducesAnEmptyPlan() {
        assertThat(service.plan(List.of()).artifacts()).isEmpty();
    }

    @Test
    void aNullCatalogIsRejected() {
        assertThatThrownBy(() -> new ApplyService(null, store))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNullStoreIsRejected() {
        assertThatThrownBy(() -> new ApplyService(CyntexCatalog.load(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- upsert by id, hash-unchanged = no-op ----

    @Test
    void applyToAnEmptyStoreCreatesTheResourceAndWritesOnce() {
        ApplyResult result = service.apply(List.of(draft(TGT_MY)));

        assertThat(result.outcomes()).singleElement().satisfies(o -> {
            assertThat(o.id()).isEqualTo("tgt_my");
            assertThat(o.kind()).isEqualTo("source");
            assertThat(o.change()).isEqualTo(ArtifactOutcome.Change.CREATED);
            assertThat(o.contentHash()).matches("[0-9a-f]{64}");
        });
        assertThat(store.saveCount).as("a create writes exactly once").isEqualTo(1);
        assertThat(store.get("tgt_my")).isPresent();
    }

    @Test
    void reapplyingIdenticalContentIsANoOpAndDoesNotWrite() {
        // The core no-op guarantee: applying the same resource twice writes only once; the second apply
        // reads the stored artifact, finds an equal content hash, and skips the store write.
        service.apply(List.of(draft(TGT_MY)));

        ApplyResult second = service.apply(List.of(draft(TGT_MY)));

        assertThat(second.outcomes()).singleElement()
                .extracting(ArtifactOutcome::change).isEqualTo(ArtifactOutcome.Change.UNCHANGED);
        assertThat(store.saveCount).as("re-applying unchanged content performs no second write").isEqualTo(1);
    }

    @Test
    void theNoOpIsKeyedByCanonicalHashNotRawText() {
        // Re-apply the same resource with a different raw config key order. It canonicalizes and hashes
        // identically, so it is still a no-op — the idempotency key is the hash over the canonical form.
        String reordered = """
                version: cyntex/v1
                kind: source
                id: tgt_my
                connector: mysql
                config: { password: My_2026, username: writer, host: 10.30.0.5 }
                """;
        service.apply(List.of(draft(TGT_MY)));

        ApplyResult second = service.apply(List.of(draft(reordered)));

        assertThat(second.outcomes()).singleElement()
                .extracting(ArtifactOutcome::change).isEqualTo(ArtifactOutcome.Change.UNCHANGED);
        assertThat(store.saveCount).isEqualTo(1);
    }

    @Test
    void reapplyingChangedContentUpdatesAndWritesAgain() {
        String changed = """
                version: cyntex/v1
                kind: source
                id: tgt_my
                connector: mysql
                config: { host: 10.30.0.5, username: writer, password: Changed_2026 }
                """;
        service.apply(List.of(draft(TGT_MY)));

        ApplyResult second = service.apply(List.of(draft(changed)));

        assertThat(second.outcomes()).singleElement()
                .extracting(ArtifactOutcome::change).isEqualTo(ArtifactOutcome.Change.UPDATED);
        assertThat(store.saveCount).as("changed content writes a second time").isEqualTo(2);
        // The store now holds the changed canonical form (server-as-truth: the last write wins).
        assertThat(new CanonicalWriter().write(store.get("tgt_my").orElseThrow()))
                .contains("Changed_2026");
    }

    @Test
    void aMultiResourceBatchUpsertsEachByIdInSubmissionOrder() {
        ApplyResult result = service.apply(List.of(draft(SRC_ORA), draft(PIPELINE), draft(TGT_MY)));

        assertThat(result.outcomes()).extracting(ArtifactOutcome::id)
                .containsExactly("src_ora", "ora2my_ods", "tgt_my");
        assertThat(result.outcomes()).extracting(ArtifactOutcome::change)
                .containsOnly(ArtifactOutcome.Change.CREATED);
        assertThat(store.saveCount).isEqualTo(3);
    }

    @Test
    void aMixedBatchWritesOnlyTheChangedAndNewResources() {
        // Seed tgt_my. Then apply a batch of [tgt_my unchanged, src_ora new]: only the new resource is
        // written — the no-op is decided per artifact, not per batch.
        service.apply(List.of(draft(TGT_MY)));
        assertThat(store.saveCount).isEqualTo(1);

        ApplyResult result = service.apply(List.of(draft(TGT_MY), draft(SRC_ORA_STANDALONE)));

        assertThat(result.outcomes()).extracting(ArtifactOutcome::id).containsExactly("tgt_my", "src_ora");
        assertThat(result.outcomes()).extracting(ArtifactOutcome::change)
                .containsExactly(ArtifactOutcome.Change.UNCHANGED, ArtifactOutcome.Change.CREATED);
        assertThat(store.saveCount).as("only the new resource is written").isEqualTo(2);
    }

    @Test
    void anInvalidResourceInTheBatchAbortsBeforeAnyWrite() {
        // Validation runs over the whole batch before any upsert, so an invalid member leaves the store
        // untouched — no partial write. (Mid-batch write-failure atomicity is a later concern; this
        // asserts only the validation-failure guarantee.)
        String bad = TGT_MY + "bogus_field: 1\n";

        Throwable t = catchThrowable(() -> service.apply(List.of(draft(SRC_ORA), draft(bad))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(store.saveCount).as("a validation failure writes nothing").isZero();
        assertThat(store.get("src_ora")).isEmpty();
    }

    // ---- fixtures ----

    private static final String SRC_ORA = """
            version: cyntex/v1
            kind: source
            id: src_ora
            connector: oracle
            config: { host: 10.20.0.15, port: 1521, service_name: ORCL,
                      username: cdc_user, password: Ora_2026 }
            mode: cdc
            tables: [ ORDERS, ORDER_ITEMS, CUSTOMERS ]
            options: { include_ddl: true }
            """;

    // The same oracle source with no pipeline referencing it — a standalone resource for batch tests.
    private static final String SRC_ORA_STANDALONE = SRC_ORA;

    private static final String PIPELINE = """
            version: cyntex/v1
            kind: pipeline
            id: ora2my_ods
            source: src_ora
            settings: { read_mode: snapshot_and_cdc }
            serve:
              from: /.*/
              sync:
                - id: my_ods
                  source: tgt_my
                  write_mode: upsert
                  ddl: apply
            """;

    /**
     * An in-memory {@link ArtifactStore} that mirrors the Mongo store's canonical round-trip — it holds
     * each artifact as its canonical text and reconstructs it on read through the parser — and counts
     * writes so a test can assert the no-op performs no store write.
     */
    private static final class RecordingArtifactStore implements ArtifactStore {

        private final CanonicalWriter writer = new CanonicalWriter();
        private final DslParser parser = new DslParser();
        private final Map<String, String> byId = new LinkedHashMap<>();
        private int saveCount = 0;

        @Override
        public void save(Resource artifact) {
            saveCount++;
            byId.put(artifact.id(), writer.write(artifact));
        }

        @Override
        public Optional<Resource> get(String id) {
            String canonical = byId.get(id);
            return canonical == null ? Optional.empty() : Optional.of(parser.parse(canonical));
        }

        @Override
        public List<Resource> list() {
            List<Resource> resources = new ArrayList<>();
            for (String canonical : byId.values()) {
                resources.add(parser.parse(canonical));
            }
            return resources;
        }
    }
}
