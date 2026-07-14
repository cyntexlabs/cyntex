package io.cyntex.app;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import io.cyntex.adapters.transform.MapSpec;
import io.cyntex.adapters.transform.StatelessTransforms;
import io.cyntex.core.model.FromRef;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.Step;
import io.cyntex.core.model.SyncElement;
import io.cyntex.core.model.TableRef;
import io.cyntex.core.model.TransformBody;
import io.cyntex.runtime.engine.DagBindings;
import io.cyntex.runtime.engine.PipelineDagBuilder;
import io.cyntex.runtime.srs.MiningChainId;
import io.cyntex.runtime.srs.SrsReadCursorPublisherFactory;
import io.cyntex.runtime.srs.SrsRingbuffer;
import io.cyntex.runtime.srs.SrsSourceProcessor;
import io.cyntex.runtime.srs.StartFrom;
import io.cyntex.spi.capture.CaptureConfig;
import io.cyntex.spi.sink.DdlPolicy;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteMode;
import io.cyntex.spi.store.ArtifactStore;
import io.cyntex.spi.store.StorePort;
import io.cyntex.spi.transform.TransformPort;
import io.cyntex.core.common.CyntexException;
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
 * <p>L1 shape: each source reads exactly one table, and a serve.sync element names a source id as its target
 * connection supplier. Start position defaults to the earliest buffered change.
 */
final class StoreBackedDagSource implements DagSource {

    private final StorePort storePort;

    StoreBackedDagSource(StorePort storePort) {
        this.storePort = Objects.requireNonNull(storePort, "storePort");
    }

    @Override
    public DAG dagFor(String pipelineId) {
        PipelineResource pipeline = loadPipeline(pipelineId);
        return PipelineDagBuilder.build(pipeline, bindings());
    }

    private PipelineResource loadPipeline(String pipelineId) {
        Resource resource = artifacts().get(pipelineId).orElseThrow(() -> new CyntexException(
                ActuationError.PIPELINE_NOT_FOUND, Map.of("pipeline", pipelineId), null));
        if (!(resource instanceof PipelineResource pipeline)) {
            throw new CyntexException(ActuationError.NOT_A_PIPELINE,
                    Map.of("pipeline", pipelineId, "kind", resource.kind()), null);
        }
        return pipeline;
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
     * the mining-chain identity and, from it, the per-table change ring the capture side writes. The stream
     * name projected into each event is the table name. Deriving the same identity the capture side derives
     * is what points the reader at the ring the writer fills.
     */
    private ProcessorMetaSupplier sourceVertex(String sourceId) {
        SourceResource source = loadSource(sourceId);
        String table = singleTable(source);
        CaptureConfig config = new CaptureConfig(source.connector(), source.config(), List.of(table));
        String srsKey = source.srs() != null ? source.srs().key() : null;
        String ringName = SrsRingbuffer.ringName(MiningChainId.resolve(config, srsKey).value(), table);
        return SrsSourceProcessor.metaSupplier(
                ringName, table, StartFrom.earliest(), SrsReadCursorPublisherFactory.NONE);
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
     * come from the element, defaulting to upsert and fail. The factory carries only these serializable
     * coordinates and opens the connector on the member that runs the sink.
     */
    private SupplierEx<? extends SinkWriter> sinkWriter(SyncElement element) {
        SourceResource target = loadSource(element.source());
        return new PdkSinkWriterFactory(
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

    private SourceResource loadSource(String sourceId) {
        Resource resource = artifacts().get(sourceId).orElseThrow(() -> new IllegalStateException(
                "source '" + sourceId + "' referenced by a pipeline is not in the store"));
        if (!(resource instanceof SourceResource source)) {
            throw new IllegalStateException("resource '" + sourceId
                    + "' referenced as a source is a '" + resource.kind() + "'");
        }
        return source;
    }

    /** The one table an L1 source reads; a missing, multi-table or regex selector is out of scope here. */
    private static String singleTable(SourceResource source) {
        List<TableRef> tables = source.tables();
        if (tables == null || tables.size() != 1) {
            throw new IllegalStateException("source '" + source.id() + "' must read exactly one table, declares "
                    + (tables == null ? 0 : tables.size()));
        }
        return switch (tables.get(0)) {
            case TableRef.Literal literal -> literal.name();
            case TableRef.Spec spec -> spec.name();
            case TableRef.Regex regex -> throw new IllegalStateException("source '" + source.id()
                    + "' selects tables by regex, which the linear L1 builder does not carry: " + regex.pattern());
        };
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
}
