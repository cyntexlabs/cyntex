package io.cyntex.core.dsl;

import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.ServeBlock;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B3-5: the F8 / §2 id rules. Two invariants:
 * <ul>
 *   <li><b>charset</b> — an id must not contain the reserved addressing separator {@code '.'}
 *       (used for stream addressing {@code <id>.<table>} and qualified private instance ids).
 *       Enforced on every resource id, top-level and inline.</li>
 *   <li><b>workspace uniqueness</b> — top-level ids (kind resources + pipelines) share one
 *       namespace and must be unique across a batch.</li>
 * </ul>
 * The qualified private form {@code <pipeline_id>.<id>} is a runtime/control-plane identifier
 * only; it never appears in DSL text, so there is nothing to parse for it here.
 */
class DslIdRulesTest {

    private static final Path CORPUS = Path.of("src", "test", "resources", "corpus");

    private final DslParser parser = new DslParser();

    @Test
    void topLevelIdWithDotRejected() {
        // corpus invalid/g01: id 'src.orders' contains the reserved '.' separator (§2).
        Throwable thrown = catchThrowable(() -> parser.parse(
                corpus("invalid/g01-id-contains-dot/src_bad_id.cyn.yml")));

        assertThat(thrown).isInstanceOf(DslException.class);
        DslException ex = (DslException) thrown;
        assertThat(ex.code()).isEqualTo(DslError.ILLEGAL_VALUE);
        assertThat(ex.path()).isEqualTo("id");
    }

    @Test
    void inlineStepIdWithDotRejected() {
        // The '.' reservation applies at every id level: an inline step id becomes part of the
        // runtime <pipeline_id>.<id> address, so a dot in it would corrupt that addressing.
        String yaml = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src
                transforms:
                  - id: my.step
                    from: [t]
                    type: filter
                    expr: "op != 'd'"
                """;

        Throwable thrown = catchThrowable(() -> parser.parse(yaml));

        assertThat(thrown).isInstanceOf(DslException.class);
        DslException ex = (DslException) thrown;
        assertThat(ex.code()).isEqualTo(DslError.ILLEGAL_VALUE);
        assertThat(ex.path()).isEqualTo("transforms[0].id");
    }

    @Test
    void duplicateTopLevelIdRejected() {
        // corpus invalid/g02: two resources in one batch declare id 'src_orders' (§2/F8).
        Resource mysql = parser.parse(corpus("invalid/g02-duplicate-top-level-id/src_orders_mysql.cyn.yml"));
        Resource postgres = parser.parse(corpus("invalid/g02-duplicate-top-level-id/src_orders_pg.cyn.yml"));

        Throwable thrown = catchThrowable(() -> Workspace.of(List.of(mysql, postgres)));

        assertThat(thrown).isInstanceOf(DslException.class);
        DslException ex = (DslException) thrown;
        assertThat(ex.code()).isEqualTo(DslError.DUPLICATE_ID);
        assertThat(ex.path()).isEqualTo("id");
        assertThat(ex.getMessage()).contains("src_orders");
    }

    @Test
    void distinctIdsFormWorkspaceAddressableById() {
        Resource crm = parser.parse(corpus("valid/s11-reuse-assembly/src_crm.cyn.yml"));
        Resource maskPii = parser.parse(corpus("valid/s11-reuse-assembly/mask_pii.cyn.yml"));

        Workspace ws = Workspace.of(List.of(crm, maskPii));

        assertThat(ws.resource("src_crm")).isSameAs(crm);
        assertThat(ws.resource("mask_pii")).isSameAs(maskPii);
        assertThat(ws.resources()).containsExactlyInAnyOrder(crm, maskPii);
    }

    @Test
    void anonymousServeSyncPushGetGeneratedIds() {
        // 2026-06-15 decision: every omitted inline id is auto-generated and written to canonical
        // (positional scheme, canonical-form §5). Coverage now extends past transforms step / inline
        // view to the serve block (-> 'serve') and its sync / push elements (-> sync_<N> / push_<N>),
        // so every node carries a visible <pipeline_id>.<id> address.
        String yaml = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src
                serve:
                  from: /.*/
                  sync:
                    - { source: tgt_a }
                    - { source: tgt_b }
                  push:
                    - { source: tgt_k }
                """;

        PipelineResource p = (PipelineResource) parser.parse(yaml);
        ServeBlock.Inline serve = (ServeBlock.Inline) p.serve();

        assertThat(serve.id()).isEqualTo("serve");
        assertThat(serve.sync().get(0).id()).isEqualTo("sync_1");
        assertThat(serve.sync().get(1).id()).isEqualTo("sync_2");
        assertThat(serve.push().get(0).id()).isEqualTo("push_1");
    }

    @Test
    void duplicatePipelineInternalIdRejected() {
        // corpus invalid/g03: two transforms steps share id 'norm'. Pipeline-internal ids share one
        // namespace (the runtime <pipeline_id>.<id> address) and must be unique (2026-06-15 decision).
        Resource src = parser.parse(corpus("invalid/g03-duplicate-pipeline-internal-id/src_a.cyn.yml"));
        Resource pipe = parser.parse(corpus("invalid/g03-duplicate-pipeline-internal-id/p_dup.cyn.yml"));

        Throwable thrown = catchThrowable(() -> Workspace.of(List.of(src, pipe)));

        assertThat(thrown).isInstanceOf(DslException.class);
        DslException ex = (DslException) thrown;
        assertThat(ex.code()).isEqualTo(DslError.DUPLICATE_ID);
        assertThat(ex.path()).isEqualTo("transforms[1].id");
    }

    @Test
    void stepAndSyncSharingIdRejected() {
        // The internal namespace is flat across step / view / serve / sync / push ids: a step and a
        // sync element with the same id collide (both resolve to <pipeline_id>.dup at runtime). The
        // second occurrence (the sync) is reported.
        String src = """
                version: cyntex/v1
                kind: source
                id: src_a
                connector: mysql
                mode: cdc
                tables: [ orders ]
                """;
        String pipe = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                transforms:
                  - { id: dup, from: [orders], type: filter, expr: "op != 'd'" }
                serve:
                  from: dup
                  sync: [ { id: dup, source: src_a } ]
                """;

        Throwable thrown = catchThrowable(() -> Workspace.of(List.of(parser.parse(src), parser.parse(pipe))));

        assertThat(thrown).isInstanceOf(DslException.class);
        DslException ex = (DslException) thrown;
        assertThat(ex.code()).isEqualTo(DslError.DUPLICATE_ID);
        assertThat(ex.path()).isEqualTo("serve.sync[0].id");
    }

    private static String corpus(String relative) {
        try {
            return Files.readString(CORPUS.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
