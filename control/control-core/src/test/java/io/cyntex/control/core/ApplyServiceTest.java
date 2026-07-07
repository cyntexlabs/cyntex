package io.cyntex.control.core;

import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.dsl.DslError;
import io.cyntex.core.dsl.DslException;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The resource-type-agnostic apply front half (plan l1-p1b B1): validate -> canonical -> hash, no
 * store. A draft that fails any validation tier (structural, reference closure, connector capability
 * matrix, batch duplicate id) aborts the plan with its coded {@code dsl.*} diagnostic; nothing is
 * prepared for upsert. The content hash is taken over the canonical form, so re-applying unchanged
 * content re-hashes identically.
 */
class ApplyServiceTest {

    private final ApplyService service = new ApplyService(CyntexCatalog.load());

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
        // The s01 corpus workspace: an oracle CDC source, a mirror pipeline, a mysql write target.
        String srcOra = """
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
        String pipeline = """
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

        ApplyPlan plan = service.plan(List.of(draft(srcOra), draft(pipeline), draft(TGT_MY)));

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
        assertThatThrownBy(() -> new ApplyService(null))
                .isInstanceOf(NullPointerException.class);
    }
}
