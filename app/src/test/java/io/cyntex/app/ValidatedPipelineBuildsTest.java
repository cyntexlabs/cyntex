package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Vertex;
import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.dsl.Workspace;
import io.cyntex.core.model.Resource;
import io.cyntex.spi.sink.SinkWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The seam between the two halves of a pipeline's life: every pipeline the product's own validator
 * accepts, the builder must be able to build.
 *
 * <p>Nothing else checks this. Both halves are covered, and both hand-build their pipelines - differently.
 * The validator's own corpus addresses a source by the table it reads ({@code from: [orders]}); every DAG
 * builder test addresses it by its source id ({@code FromRef.literal("orders_src")}) and injects the
 * reference-to-vertex map besides, which defines away the very mapping production has to get right. Two
 * green suites describing two different products, and no test where they meet.
 *
 * <p>So this one parses with the product's parser, validates with the product's rules, and hands what
 * comes out to the builder. A disagreement between them has nowhere left to hide.
 */
class ValidatedPipelineBuildsTest {

    private static final String SOURCE = """
            version: cyntex/v1
            kind: source
            id: orders_src
            connector: mysql
            config: { host: h }
            mode: cdc
            tables: [ orders ]
            """;

    private static final String TARGET = """
            version: cyntex/v1
            kind: source
            id: orders_dest
            connector: mongodb
            config: { uri: u }
            """;

    /**
     * The shape the product's own valid corpus writes: a transform addressing the table its source reads,
     * a serve addressing the transform. Addressing the source by its id instead is what every builder test
     * does, and the validator rejects it.
     */
    private static final String PIPELINE = """
            version: cyntex/v1
            kind: pipeline
            id: p
            source: orders_src
            transforms:
              - { id: keep_even, from: [orders], type: filter, expr: "after.id % 2 == 0" }
            serve:
              from: keep_even
              sync: [ { id: sync_1, source: orders_dest } ]
            """;

    /** A source with no transform between it and the sink: the serve addresses the source's table. */
    private static final String DIRECT_PIPELINE = """
            version: cyntex/v1
            kind: pipeline
            id: direct
            source: orders_src
            serve:
              from: orders
              sync: [ { id: sync_1, source: orders_dest } ]
            """;

    @Test
    void aValidatedPipelineWithATransformBuildsIntoADag() {
        InMemoryStorePort store = validated(SOURCE, TARGET, PIPELINE);

        DAG dag = new StoreBackedDagSource(store, discardingBinder()).dagFor("p");

        assertThat(vertexNames(dag)).containsExactlyInAnyOrder("orders_src", "keep_even", "serve.sync_1");
    }

    /**
     * The serve reaches the source directly, so its reference names a table and must still find the source's
     * vertex. The vertex is keyed by the source id, and nothing but this mapping bridges the two.
     */
    @Test
    void aValidatedPipelineServingItsSourceDirectlyBuildsIntoADag() {
        InMemoryStorePort store = validated(SOURCE, TARGET, DIRECT_PIPELINE);

        DAG dag = new StoreBackedDagSource(store, discardingBinder()).dagFor("direct");

        assertThat(vertexNames(dag)).containsExactlyInAnyOrder("orders_src", "serve.sync_1");
    }

    // ---- fixtures ----------------------------------------------------------------------

    /** Parses and validates through the product's own gate, then stores what it accepted. */
    private static InMemoryStorePort validated(String... documents) {
        DslParser parser = new DslParser();
        List<Resource> resources = new ArrayList<>();
        for (String document : documents) {
            resources.add(parser.parse(document));
        }
        // The acceptance gate an apply runs. A fixture this throws on is a fixture no author could write.
        Workspace.of(resources);
        InMemoryStorePort store = new InMemoryStorePort();
        resources.forEach(store.artifacts()::save);
        return store;
    }

    private static StoreBackedDagSource.SinkWriterBinder discardingBinder() {
        return (connectorId, settings, writeMode, ddl, target) -> (SupplierEx<SinkWriter>) () -> null;
    }

    private static List<String> vertexNames(DAG dag) {
        List<String> names = new ArrayList<>();
        for (Vertex vertex : dag) {
            names.add(vertex.getName());
        }
        return names;
    }
}
