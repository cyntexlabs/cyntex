package io.cyntex.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.function.FunctionEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.Processors;
import io.cyntex.core.event.Envelope;
import io.cyntex.core.model.FieldRule;
import io.cyntex.core.model.FromClause;
import io.cyntex.core.model.FromRef;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.ServeBlock;
import io.cyntex.core.model.Step;
import io.cyntex.core.model.SyncElement;
import io.cyntex.core.model.TransformBody;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteResult;
import io.cyntex.spi.transform.TransformPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Structure-assert coverage for the pipeline DAG builder: builds a DAG from a hand-built
 * {@link PipelineResource} with injected fake bindings and asserts the vertex and edge topology,
 * without running a Jet job. All leaves (source, sink, transform ports) are injected doubles, so
 * these tests carry no dependency on the SRS source path.
 */
class PipelineDagBuilderTest {

    @Test
    void source_to_serve_without_transforms_is_a_source_then_sink() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                null,
                null,
                serve(FromRef.literal("orders_src"), sync("sync_1", "orders_dest")),
                null, null);

        DAG dag = PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"))));

        assertThat(vertexNames(dag)).containsExactlyInAnyOrder("orders_src", "serve.sync_1");
        assertThat(edges(dag)).containsExactly(edge("orders_src", "serve.sync_1"));
    }

    @Test
    void stateless_step_wires_source_through_a_transform_vertex_to_sink() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                List.of(filter("keep_even", "row.id % 2 == 0", FromRef.literal("orders_src"))),
                null,
                serve(FromRef.literal("keep_even"), sync("sync_1", "orders_dest")),
                null, null);

        DAG dag = PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"),
                FromRef.literal("keep_even"), List.of("keep_even"))));

        assertThat(vertexNames(dag))
                .containsExactlyInAnyOrder("orders_src", "keep_even", "serve.sync_1");
        assertThat(edges(dag)).containsExactlyInAnyOrder(
                edge("orders_src", "keep_even"),
                edge("keep_even", "serve.sync_1"));
    }

    @Test
    void linear_chain_wires_a_vertex_per_step_in_declared_order() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                List.of(
                        filter("f", "row.id > 0", FromRef.literal("orders_src")),
                        map("m", FromRef.literal("f")),
                        js("j", FromRef.literal("m"))),
                null,
                serve(FromRef.literal("j"), sync("sync_1", "orders_dest")),
                null, null);

        DAG dag = PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"),
                FromRef.literal("f"), List.of("f"),
                FromRef.literal("m"), List.of("m"),
                FromRef.literal("j"), List.of("j"))));

        assertThat(vertexNames(dag))
                .containsExactlyInAnyOrder("orders_src", "f", "m", "j", "serve.sync_1");
        assertThat(edges(dag)).containsExactlyInAnyOrder(
                edge("orders_src", "f"),
                edge("f", "m"),
                edge("m", "j"),
                edge("j", "serve.sync_1"));
    }

    @Test
    void union_merges_upstreams_into_a_passthrough_vertex_without_a_transform_port() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("a_src", "b_src"),
                List.of(union("u", FromRef.literal("a_src"), FromRef.literal("b_src"))),
                null,
                serve(FromRef.literal("u"), sync("sync_1", "orders_dest")),
                null, null);

        DagBindings bindings = new DagBindings(
                srcId -> stubMeta(),
                step -> {
                    throw new AssertionError("union must not consult transformPorts");
                },
                syncElement -> stubWriter(),
                ref -> Map.of(
                        FromRef.literal("a_src"), List.of("a_src"),
                        FromRef.literal("b_src"), List.of("b_src"),
                        FromRef.literal("u"), List.of("u")).getOrDefault(ref, List.of()));

        DAG dag = PipelineDagBuilder.build(pipeline, bindings);

        assertThat(vertexNames(dag))
                .containsExactlyInAnyOrder("a_src", "b_src", "u", "serve.sync_1");
        assertThat(edges(dag)).containsExactlyInAnyOrder(
                edge("a_src", "u", 0, 0),
                edge("b_src", "u", 0, 1),
                edge("u", "serve.sync_1", 0, 0));
    }

    @Test
    void multi_ref_stateless_step_merges_all_upstreams_by_fan_in() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("a_src", "b_src"),
                List.of(filter("f", "true", FromRef.literal("a_src"), FromRef.literal("b_src"))),
                null,
                serve(FromRef.literal("f"), sync("sync_1", "orders_dest")),
                null, null);

        DAG dag = PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("a_src"), List.of("a_src"),
                FromRef.literal("b_src"), List.of("b_src"),
                FromRef.literal("f"), List.of("f"))));

        assertThat(edges(dag)).containsExactlyInAnyOrder(
                edge("a_src", "f", 0, 0),
                edge("b_src", "f", 0, 1),
                edge("f", "serve.sync_1", 0, 0));
    }

    @Test
    void multiple_sync_elements_fan_out_from_the_serve_upstream() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                null,
                null,
                serve(FromRef.literal("orders_src"),
                        sync("sync_1", "dest_a"), sync("sync_2", "dest_b")),
                null, null);

        DAG dag = PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"))));

        assertThat(vertexNames(dag))
                .containsExactlyInAnyOrder("orders_src", "serve.sync_1", "serve.sync_2");
        assertThat(edges(dag)).containsExactlyInAnyOrder(
                edge("orders_src", "serve.sync_1", 0, 0),
                edge("orders_src", "serve.sync_2", 1, 0));
    }

    @Test
    void stateful_join_step_is_out_of_scope_and_rejected() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                List.of(joinStep("j", FromRef.literal("orders_src"))),
                null,
                serve(FromRef.literal("j"), sync("sync_1", "orders_dest")),
                null, null);

        assertThatThrownBy(() -> PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"),
                FromRef.literal("j"), List.of("j")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("j");
    }

    @Test
    void use_reference_step_is_not_yet_resolved_and_rejected() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                List.of(Step.use("u", "shared_filter", FromClause.list(FromRef.literal("orders_src")), null)),
                null,
                serve(FromRef.literal("u"), sync("sync_1", "orders_dest")),
                null, null);

        assertThatThrownBy(() -> PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"),
                FromRef.literal("u"), List.of("u")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void use_reference_serve_block_is_not_yet_resolved_and_rejected() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                null,
                null,
                new ServeBlock.Use(null, "shared_serve", FromRef.literal("orders_src")),
                null, null);

        assertThatThrownBy(() -> PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reference_that_resolves_to_nothing_is_an_invariant_violation() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                List.of(filter("f", "true", FromRef.literal("ghost"))),
                null,
                serve(FromRef.literal("f"), sync("sync_1", "orders_dest")),
                null, null);

        // "ghost" is not in the canned lookup, so it resolves to an empty upstream set.
        assertThatThrownBy(() -> PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"),
                FromRef.literal("f"), List.of("f")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reference_to_an_unknown_vertex_key_is_an_invariant_violation() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                List.of(filter("f", "true", FromRef.literal("orders_src"))),
                null,
                serve(FromRef.literal("f"), sync("sync_1", "orders_dest")),
                null, null);

        // The resolver returns a key for which no vertex was ever built.
        assertThatThrownBy(() -> PipelineDagBuilder.build(pipeline, bindings(Map.of(
                FromRef.literal("orders_src"), List.of("orders_src"),
                FromRef.literal("f"), List.of("no_such_vertex")))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- fixtures ----------------------------------------------------------------------

    /** A binder whose leaves are structural stubs; {@code upstreams} is a canned lookup. */
    private static DagBindings bindings(Map<FromRef, List<String>> upstreams) {
        return new DagBindings(
                srcId -> stubMeta(),
                step -> (SupplierEx<TransformPort>) () -> ev -> List.of(ev),
                syncElement -> stubWriter(),
                Function.<FromRef>identity().andThen(ref -> upstreams.getOrDefault(ref, List.of())));
    }

    /** A structurally valid, behaviourally irrelevant vertex supplier for graph-shape assertions. */
    private static ProcessorMetaSupplier stubMeta() {
        return ProcessorMetaSupplier.of(Processors.mapP(FunctionEx.identity()));
    }

    /** A behaviourally irrelevant sink-writer factory; the builder wraps it but never opens it here. */
    private static SupplierEx<SinkWriter> stubWriter() {
        return NoOpSinkWriter::new;
    }

    private static final class NoOpSinkWriter implements SinkWriter {
        @Override
        public CompletionStage<WriteResult> write(List<Envelope> records) {
            return CompletableFuture.completedFuture(new WriteResult(records.size()));
        }

        @Override
        public void close() {
        }
    }

    private static ServeBlock serve(FromRef from, SyncElement... sync) {
        return new ServeBlock.Inline(null, from, List.of(sync), null, null);
    }

    private static SyncElement sync(String id, String dest) {
        return new SyncElement(id, dest, null, null, null, null);
    }

    private static Step filter(String id, String expr, FromRef... from) {
        return Step.inline(id, FromClause.list(from), new TransformBody.Filter(expr), null, null);
    }

    private static Step map(String id, FromRef... from) {
        TransformBody body = new TransformBody.MapProjection(Map.of("out", FieldRule.rename("in")));
        return Step.inline(id, FromClause.list(from), body, null, null);
    }

    private static Step js(String id, FromRef... from) {
        return Step.inline(id, FromClause.list(from), new TransformBody.Js("emit(row)"), null, null);
    }

    private static Step union(String id, FromRef... from) {
        return Step.inline(id, FromClause.list(from), new TransformBody.Union(), null, null);
    }

    private static Step joinStep(String id, FromRef from) {
        TransformBody body = new TransformBody.Join("duckdb", "SELECT 1");
        return Step.inline(id, FromClause.aliases(Map.of("root", from)), body, null, null);
    }

    private static List<String> vertexNames(DAG dag) {
        List<String> names = new ArrayList<>();
        for (Vertex v : dag) {
            names.add(v.getName());
        }
        return names;
    }

    /** All edges as {@code "src->dest#srcOrd,destOrd"} strings, for order-insensitive assertions. */
    private static List<String> edges(DAG dag) {
        List<String> out = new ArrayList<>();
        for (Vertex v : dag) {
            for (Edge e : dag.getOutboundEdges(v.getName())) {
                out.add(e.getSourceName() + "->" + e.getDestName()
                        + "#" + e.getSourceOrdinal() + "," + e.getDestOrdinal());
            }
        }
        return out;
    }

    private static String edge(String src, String dest) {
        return edge(src, dest, 0, 0);
    }

    private static String edge(String src, String dest, int srcOrd, int destOrd) {
        return src + "->" + dest + "#" + srcOrd + "," + destOrd;
    }
}
