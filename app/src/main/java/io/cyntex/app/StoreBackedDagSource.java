package io.cyntex.app;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import io.cyntex.adapters.transform.MapSpec;
import io.cyntex.adapters.transform.StatelessTransforms;
import io.cyntex.core.model.FromRef;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.Step;
import io.cyntex.core.model.SyncElement;
import io.cyntex.core.model.TransformBody;
import io.cyntex.runtime.engine.DagBindings;
import io.cyntex.runtime.engine.PipelineDagBuilder;
import io.cyntex.runtime.engine.SinkAckBinding;
import io.cyntex.runtime.srs.SrsReadCursorPublisherFactory;
import io.cyntex.runtime.srs.SrsSourceProcessor;
import io.cyntex.runtime.srs.StartFrom;
import io.cyntex.spi.sink.DdlPolicy;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteMode;
import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.StorePort;
import io.cyntex.spi.transform.TransformPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the Jet topology a pipeline runs from its stored artifact. It loads the pipeline and the source and
 * target artifacts it references, turns each into the leaf and reference bindings the engine's DAG builder
 * needs, and returns the DAG the builder assembles - source vertices to a linear transform chain to serve
 * sink vertices. The builder owns the topology; this owns how each leaf resolves from the store.
 *
 * <p>Only serializable coordinates cross onto the DAG: a source vertex carries its resolved change-ring name
 * and stream name, a transform vertex carries the port's serializable shape, and a sink vertex carries the
 * resolved connector coordinates and resolves the connector on the member that opens it. The reference and
 * leaf resolution itself runs here, on the assembly side, so nothing store-bound is shipped.
 *
 * <p>The sink-writer factory is a constructor seam: production binds the PDK factory that resolves the
 * connector member-side, while a data-flow test can bind a capturing sink so the topology runs without a real
 * connector. Deriving the source's change-ring identity is delegated to the shared source resolution so the
 * reader built here and the capture side that fills the ring land on the same ring.
 *
 * <p>L1 shape: each source reads exactly one table, and a serve.sync element names a source id as its target
 * connection supplier. Start position defaults to the earliest buffered change.
 */
final class StoreBackedDagSource implements DagSource {

    private final StorePort storePort;
    private final SinkWriterBinder sinkWriterBinder;

    StoreBackedDagSource(StorePort storePort) {
        this(storePort, PdkSinkWriterFactory::new);
    }

    StoreBackedDagSource(StorePort storePort, SinkWriterBinder sinkWriterBinder) {
        this.storePort = Objects.requireNonNull(storePort, "storePort");
        this.sinkWriterBinder = Objects.requireNonNull(sinkWriterBinder, "sinkWriterBinder");
    }

    @Override
    public DAG dagFor(String pipelineId) {
        PipelineResource pipeline = StoredArtifacts.requirePipeline(artifacts(), pipelineId);
        return PipelineDagBuilder.build(pipeline, bindings(), sinkAckBinding(pipeline, pipelineId));
    }

    /**
     * The sink-ack wiring that closes the durable frontier: as a sink confirms writes it advances the
     * pipeline consumer's durable sink-acked position through this. The sink knows a chain only by the
     * {@code src} stream name (a table at L1), so the binding carries a table-to-chain map resolved from
     * every source the pipeline reads, and the sink-side order matches the capture watermark's order so the
     * two cannot drift. The map is built here, on the assembly side; only serializable coordinates ship.
     */
    private SinkAckBinding sinkAckBinding(PipelineResource pipeline, String pipelineId) {
        Map<String, String> chainIdByTable = new LinkedHashMap<>();
        for (String sourceId : pipeline.sources()) {
            SourceCaptureResolution resolution =
                    SourceCaptureResolution.of(StoredArtifacts.requireSource(artifacts(), sourceId));
            chainIdByTable.put(resolution.table(), resolution.chainId().value());
        }
        return new SinkAckBinding(
                new StoreBackedSinkAckFactory(chainIdByTable, pipelineId), MockPositionOrder.INSTANCE);
    }

    /**
     * The leaf and reference bindings for the builder. The four functions run on the assembly side as the
     * builder walks the topology; only the vertex suppliers they return travel onto the DAG, so they may
     * reach the store freely while what they produce stays serializable.
     */
    private DagBindings bindings() {
        return new DagBindings(
                this::sourceVertex,
                StoreBackedDagSource::transformPort,
                this::sinkWriter,
                StoreBackedDagSource::upstreams);
    }

    /**
     * The source vertex for one source id: it resolves the source's connector, config and single table into
     * the per-table change ring the capture side writes. The stream name projected into each event is the
     * table name. Resolving the same ring identity the capture side resolves is what points the reader at the
     * ring the writer fills.
     */
    private ProcessorMetaSupplier sourceVertex(String sourceId) {
        SourceCaptureResolution resolution =
                SourceCaptureResolution.of(StoredArtifacts.requireSource(artifacts(), sourceId));
        return SrsSourceProcessor.metaSupplier(
                resolution.ringName(), resolution.table(), StartFrom.earliest(), SrsReadCursorPublisherFactory.NONE);
    }

    /**
     * The port factory for one linear transform step. The builder only asks this for an inline stateless
     * step (filter / map / a scripted row transform); a union it merges itself and a stateful step it
     * refuses, so neither reaches here. The returned factory captures only the step body's serializable
     * shape - an expression string, a projection spec, a script - so it ships and rebuilds the port on the
     * member.
     */
    private static SupplierEx<? extends TransformPort> transformPort(Step step) {
        if (!(step instanceof Step.Inline inline)) {
            throw new IllegalStateException("transform step '" + step.id() + "' is not inline");
        }
        TransformBody body = inline.body();
        return switch (body) {
            case TransformBody.Filter filter -> {
                String expr = filter.expr();
                yield (SupplierEx<TransformPort>) () -> StatelessTransforms.filter(expr);
            }
            case TransformBody.MapProjection projection -> {
                MapSpec spec = MapSpec.from(projection);
                yield (SupplierEx<TransformPort>) () -> StatelessTransforms.map(spec);
            }
            case TransformBody.Js js -> {
                String script = js.script();
                yield (SupplierEx<TransformPort>) () -> StatelessTransforms.js(script);
            }
            default -> throw new IllegalStateException("transform step '" + step.id()
                    + "' has a body the linear builder does not carry: " + body.type());
        };
    }

    /**
     * The sink-writer factory for one serve.sync element. The element names a source id as its target
     * connection supplier, so the connector and config come from that source; the write mode and ddl policy
     * come from the element, defaulting to upsert and fail. The bound factory carries only these serializable
     * coordinates and opens the connector on the member that runs the sink.
     */
    private SupplierEx<? extends SinkWriter> sinkWriter(SyncElement element) {
        SourceResource target = StoredArtifacts.requireSource(artifacts(), element.source());
        return sinkWriterBinder.bind(
                target.connector(), target.config(), writeMode(element.writeMode()), ddl(element.ddl()));
    }

    /**
     * The producer vertex keys a reference names. A literal token is a source id or a step id, which is the
     * vertex key itself. A regex reference expands the source universe and is not carried by the linear L1
     * builder.
     */
    private static List<String> upstreams(FromRef ref) {
        if (ref instanceof FromRef.Literal literal) {
            return List.of(literal.ref());
        }
        throw new IllegalStateException("regex from: reference is not carried by the linear L1 builder: " + ref);
    }

    private static WriteMode writeMode(io.cyntex.core.model.WriteMode mode) {
        io.cyntex.core.model.WriteMode resolved = mode != null ? mode : io.cyntex.core.model.WriteMode.UPSERT;
        return switch (resolved) {
            case UPSERT -> WriteMode.UPSERT;
            case APPEND -> WriteMode.APPEND;
        };
    }

    private static DdlPolicy ddl(io.cyntex.core.model.DdlPolicy policy) {
        io.cyntex.core.model.DdlPolicy resolved = policy != null ? policy : io.cyntex.core.model.DdlPolicy.FAIL;
        return switch (resolved) {
            case APPLY -> DdlPolicy.APPLY;
            case IGNORE -> DdlPolicy.IGNORE;
            case FAIL -> DdlPolicy.FAIL;
        };
    }

    private ArtifactStore artifacts() {
        return storePort.artifacts();
    }

    /**
     * The seam that binds a serve.sync target's resolved connector coordinates to the sink-writer supplier
     * shipped onto the DAG. Production binds the PDK factory that resolves the connector member-side; a test
     * can bind a capturing sink so the topology runs without a real connector.
     */
    @FunctionalInterface
    interface SinkWriterBinder {

        SupplierEx<? extends SinkWriter> bind(
                String connectorId, Map<String, Object> settings, WriteMode writeMode, DdlPolicy ddl);
    }
}
