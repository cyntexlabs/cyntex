package io.cyntex.control.core;

import io.cyntex.core.catalog.CatalogEntryReader;
import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.dsl.DslError;
import io.cyntex.core.dsl.DslException;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.AuditRecord;
import io.cyntex.spi.store.AuditStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-15T10:15:30Z"), ZoneOffset.UTC);

    private final RecordingArtifactStore store = new RecordingArtifactStore();
    private final RecordingAuditStore auditStore = new RecordingAuditStore();
    private final ApplyService service =
            new ApplyService(CyntexCatalog::load, store, new AuditGate(auditStore, FIXED_CLOCK));

    /** An audit store that captures every record it is asked to write. */
    private static final class RecordingAuditStore implements AuditStore {
        final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void record(AuditRecord record) {
            records.add(record);
        }
    }

    /** An audit store that always fails, standing in for an unavailable audit backend. */
    private static final class FailingAuditStore implements AuditStore {
        @Override
        public void record(AuditRecord record) {
            throw new IllegalStateException("audit backend down");
        }
    }

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

    // A registered connector 'acme' (absent from the bundled snapshot) whose declared source modes differ
    // between the two rows — [cdc] then [snapshot] — so a cdc source is legal against the first, illegal
    // against the second; and a config field 'host' so the source's config validates.
    private static String acmeRow(String mode) {
        return ("""
                {
                  "id": "acme", "name": "Acme", "displayName": "Acme", "icon": null,
                  "group": "database", "modes": ["%MODE%"], "discovery": "catalog",
                  "sink": {"capable": false, "writeSemantics": []}, "pushOut": false,
                  "config": [{"name": "host", "type": "string", "label": {}, "required": false,
                    "default": null, "secret": false, "options": [], "visibleWhen": null}],
                  "provenance": {"connectorRepoSha": null, "specPath": "spec.json", "specContentHash": "h",
                    "pdkApiVersion": "1.0.0", "requiredLevel": null, "modeSource": {"%MODE%": "declared"}}
                }
                """).replace("%MODE%", mode);
    }

    private static final String ACME_CDC_SOURCE = """
            version: cyntex/v1
            kind: source
            id: src_a
            connector: acme
            config: { host: db }
            mode: cdc
            tables: [ orders ]
            """;

    @Test
    void planValidatesAgainstTheLiveMergedViewSoARuntimeRegisteredConnectorIsHonoured() {
        // The change's headline: plan() reads the catalog supplier per call, so a connector registered at
        // runtime is honoured without a restart and its capability matrix is enforced live. acme is
        // registered supporting [cdc] -> a cdc source validates; re-registered supporting only [snapshot] ->
        // the same source now violates its matrix. This fails if plan captured the catalog once (both plans
        // would see [cdc]) or were reverted to the fixed bundled snapshot (acme absent, the flip impossible).
        List<ConnectorCatalogEntry> registered = new ArrayList<>();
        registered.add(CatalogEntryReader.read(acmeRow("cdc")));
        Supplier<CyntexCatalog> live = () -> CyntexCatalog.merged(CyntexCatalog.load(), List.copyOf(registered));
        ApplyService liveService = new ApplyService(live, store, new AuditGate(auditStore, FIXED_CLOCK));

        assertThatCode(() -> liveService.plan(List.of(draft(ACME_CDC_SOURCE)))).doesNotThrowAnyException();

        registered.clear();
        registered.add(CatalogEntryReader.read(acmeRow("snapshot")));

        Throwable t = catchThrowable(() -> liveService.plan(List.of(draft(ACME_CDC_SOURCE))));
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
        assertThatThrownBy(() -> new ApplyService(null, store, new AuditGate(auditStore, FIXED_CLOCK)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aNullStoreIsRejected() {
        assertThatThrownBy(() -> new ApplyService(CyntexCatalog::load, null, new AuditGate(auditStore, FIXED_CLOCK)))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- upsert by id, hash-unchanged = no-op ----

    @Test
    void applyToAnEmptyStoreCreatesTheResourceAndWritesOnce() {
        ApplyResult result = service.apply("alice", List.of(draft(TGT_MY)));

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
        service.apply("alice", List.of(draft(TGT_MY)));

        ApplyResult second = service.apply("alice", List.of(draft(TGT_MY)));

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
        service.apply("alice", List.of(draft(TGT_MY)));

        ApplyResult second = service.apply("alice", List.of(draft(reordered)));

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
        service.apply("alice", List.of(draft(TGT_MY)));

        ApplyResult second = service.apply("alice", List.of(draft(changed)));

        assertThat(second.outcomes()).singleElement()
                .extracting(ArtifactOutcome::change).isEqualTo(ArtifactOutcome.Change.UPDATED);
        assertThat(store.saveCount).as("changed content writes a second time").isEqualTo(2);
        // The store now holds the changed canonical form (server-as-truth: the last write wins).
        assertThat(new CanonicalWriter().write(store.get("tgt_my").orElseThrow()))
                .contains("Changed_2026");
    }

    @Test
    void aMultiResourceBatchUpsertsEachByIdInSubmissionOrder() {
        ApplyResult result = service.apply("alice", List.of(draft(SRC_ORA), draft(PIPELINE), draft(TGT_MY)));

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
        service.apply("alice", List.of(draft(TGT_MY)));
        assertThat(store.saveCount).isEqualTo(1);

        ApplyResult result = service.apply("alice", List.of(draft(TGT_MY), draft(SRC_ORA_STANDALONE)));

        assertThat(result.outcomes()).extracting(ArtifactOutcome::id).containsExactly("tgt_my", "src_ora");
        assertThat(result.outcomes()).extracting(ArtifactOutcome::change)
                .containsExactly(ArtifactOutcome.Change.UNCHANGED, ArtifactOutcome.Change.CREATED);
        assertThat(store.saveCount).as("only the new resource is written").isEqualTo(2);
    }

    @Test
    void anInvalidResourceInTheBatchAbortsBeforeAnyWrite() {
        // Validation runs over the whole batch before any upsert, so an invalid member leaves the store
        // untouched — no partial write. This is the validation-failure half of atomic batch; the
        // write-failure half is asserted by aWriteFailureMidBatchLeavesTheStoreUnchanged.
        String bad = TGT_MY + "bogus_field: 1\n";

        Throwable t = catchThrowable(() -> service.apply("alice", List.of(draft(SRC_ORA), draft(bad))));

        assertThat(t).isInstanceOf(DslException.class);
        assertThat(store.saveCount).as("a validation failure writes nothing").isZero();
        assertThat(store.get("src_ora")).isEmpty();
    }

    // ---- atomic batch: a write failure mid-batch rolls the whole batch back ----

    @Test
    void aWriteFailureMidBatchLeavesTheStoreUnchanged() {
        // Both resources are valid, so the batch reaches the write phase; the store then fails on the
        // second write. Because apply hands the whole changed set to one atomic saveAll, the failure
        // rolls the batch back and nothing is stored — not even the first, earlier-ordered resource.
        store.failOnId = "tgt_my";

        Throwable t = catchThrowable(() -> service.apply("alice", List.of(draft(SRC_ORA), draft(TGT_MY))));

        assertThat(t).isInstanceOf(RuntimeException.class);
        assertThat(store.get("src_ora")).as("the earlier-ordered write is rolled back, not left partial").isEmpty();
        assertThat(store.get("tgt_my")).isEmpty();
        assertThat(store.list()).isEmpty();
    }

    @Test
    void applyWritesTheChangedSetAsOneAtomicBatch() {
        // Two new resources in one apply are written as a single atomic batch, not one write per
        // artifact: the store records exactly one batch carrying both ids in submission order.
        service.apply("alice", List.of(draft(SRC_ORA), draft(TGT_MY)));

        assertThat(store.saveAllBatches).containsExactly(List.of("src_ora", "tgt_my"));
    }

    // ---- no audit, no execute: apply is an audited write and leaves a record per changed artifact ----

    @Test
    void applyRecordsOneAuditEntryPerChangedArtifactAttributedToItsOwnId() {
        // artifact.apply is a registered audited verb, so the write must leave an audit record — and the
        // record's resourceId names the artifact it changed, so the log answers "who changed which one".
        service.apply("alice", List.of(draft(SRC_ORA), draft(TGT_MY)));

        assertThat(auditStore.records).hasSize(2);
        assertThat(auditStore.records).allSatisfy(record -> {
            assertThat(record.operationId()).isEqualTo("artifact.apply");
            assertThat(record.principal()).isEqualTo("alice");
        });
        assertThat(auditStore.records).extracting(AuditRecord::resourceId)
                .containsExactly("src_ora", "tgt_my");
    }

    @Test
    void applyRecordsNoAuditEntryForAnUnchangedArtifact() {
        // The no-op changes nothing, so it is not an auditable effect: re-applying identical content
        // leaves no second record, exactly as it performs no second write.
        service.apply("alice", List.of(draft(TGT_MY)));
        assertThat(auditStore.records).hasSize(1);

        service.apply("alice", List.of(draft(TGT_MY)));

        assertThat(auditStore.records)
                .as("a no-op apply mutates nothing and so records nothing")
                .hasSize(1);
    }

    @Test
    void aMixedBatchRecordsOnlyTheChangedArtifacts() {
        service.apply("alice", List.of(draft(TGT_MY)));
        auditStore.records.clear();

        service.apply("alice", List.of(draft(TGT_MY), draft(SRC_ORA_STANDALONE)));

        assertThat(auditStore.records).extracting(AuditRecord::resourceId)
                .as("the audit log mirrors the write batch — only what actually changed")
                .containsExactly("src_ora");
    }

    @Test
    void anAuditWriteFailureRefusesTheApplyAndLeavesTheStoreUntouched() {
        // No audit, no execute: if the record cannot be written the apply is refused with a coded
        // control.audit-blocked and nothing reaches the artifact store.
        ApplyService refusing = new ApplyService(
                CyntexCatalog::load, store, new AuditGate(new FailingAuditStore(), FIXED_CLOCK));

        Throwable t = catchThrowable(() -> refusing.apply("alice", List.of(draft(TGT_MY))));

        assertThat(t).isInstanceOf(CyntexException.class);
        assertThat(((CyntexException) t).code()).isEqualTo(ControlError.AUDIT_BLOCKED);
        assertThat(store.saveCount).as("an unaudited apply does not execute").isZero();
        assertThat(store.get("tgt_my")).isEmpty();
    }

    @Test
    void aNullAuditGateIsRejected() {
        assertThatThrownBy(() -> new ApplyService(CyntexCatalog::load, store, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void applyExcludesUnchangedResourcesFromTheWriteBatch() {
        // Seed tgt_my, then apply [tgt_my unchanged, src_ora new]: the one atomic batch carries only the
        // new resource — the unchanged one is not rewritten.
        service.apply("alice", List.of(draft(TGT_MY)));

        service.apply("alice", List.of(draft(TGT_MY), draft(SRC_ORA_STANDALONE)));

        assertThat(store.saveAllBatches).containsExactly(List.of("tgt_my"), List.of("src_ora"));
    }

    @Test
    void anAllNoOpBatchPerformsNoWrite() {
        // A batch whose every member is unchanged writes nothing: the changed set is empty, so the one
        // atomic batch apply performs carries no resources.
        service.apply("alice", List.of(draft(SRC_ORA), draft(TGT_MY)));
        int writesAfterSeed = store.saveCount;

        ApplyResult result = service.apply("alice", List.of(draft(SRC_ORA), draft(TGT_MY)));

        assertThat(result.outcomes()).extracting(ArtifactOutcome::change)
                .containsOnly(ArtifactOutcome.Change.UNCHANGED);
        assertThat(store.saveCount).as("a wholly-unchanged batch writes nothing further").isEqualTo(writesAfterSeed);
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
     * each artifact as its canonical text and reconstructs it on read through the parser — and models
     * the store's atomic batch write: {@code saveAll} stages the whole batch and commits it only if none
     * of it is the injected poison id, so a mid-batch failure leaves nothing written, exactly as the
     * real transaction does. It records each batch (by id) and counts resources written so a test can
     * assert the write set and that a no-op performs no store write.
     */
    private static final class RecordingArtifactStore implements ArtifactStore {

        private final CanonicalWriter writer = new CanonicalWriter();
        private final DslParser parser = new DslParser();
        private final Map<String, String> byId = new LinkedHashMap<>();
        private final List<List<String>> saveAllBatches = new ArrayList<>();
        private int saveCount = 0;
        private String failOnId = null;

        @Override
        public void saveAll(List<Resource> artifacts) {
            // Atomic: stage the whole batch, then commit it in one step — but if any member is the
            // injected poison, fail before committing anything, so no partial batch survives.
            Map<String, String> staged = new LinkedHashMap<>();
            for (Resource artifact : artifacts) {
                if (artifact.id().equals(failOnId)) {
                    throw new RuntimeException("simulated store write failure at " + failOnId);
                }
                staged.put(artifact.id(), writer.write(artifact));
            }
            byId.putAll(staged);
            saveCount += artifacts.size();
            saveAllBatches.add(artifacts.stream().map(Resource::id).toList());
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
