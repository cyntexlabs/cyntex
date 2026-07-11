package io.cyntex.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import io.cyntex.core.event.Envelope;
import io.cyntex.core.model.FromClause;
import io.cyntex.core.model.FromRef;
import io.cyntex.core.model.PipelineResource;
import io.cyntex.core.model.ServeBlock;
import io.cyntex.core.model.Step;
import io.cyntex.core.model.SyncElement;
import io.cyntex.core.model.TransformBody;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.transform.TransformPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs a DAG the builder assembled from a whole pipeline on an embedded single member: a source, a
 * real stateless filter port and the real generic sink adapter over an injected writer, wired only
 * through the bindings. Proves the built topology is a runnable Jet job, that the filter actually
 * drops rows, and that the sink adapter delivers the surviving rows to its writer - the part a
 * structural assertion cannot see. Every leaf is an injected double, so the test carries no SRS or
 * connector dependency.
 */
class PipelineDagRunTest {

    private HazelcastInstance member;

    @BeforeEach
    void startMember() {
        Config config = new Config();
        config.getJetConfig().setEnabled(true).setCooperativeThreadCount(2);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        member = Hazelcast.newHazelcastInstance(config);
    }

    @AfterEach
    void stopMember() {
        if (member != null) {
            member.shutdown();
        }
    }

    @Test
    void built_source_filter_sink_dag_runs_and_the_filter_drops_odd_rows() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("orders_src"),
                List.of(Step.inline("keep_even",
                        FromClause.list(FromRef.literal("orders_src")),
                        new TransformBody.Filter("row.id % 2 == 0"), null, null)),
                null,
                new ServeBlock.Inline(null, FromRef.literal("keep_even"),
                        List.of(new SyncElement("sync_1", "orders_dest", null, null, null, null)),
                        null, null),
                null, null);

        SupplierEx<TransformPort> keepEvenIds = () -> event ->
                ((Integer) event.after().get("id")) % 2 == 0 ? List.of(event) : List.of();
        SupplierEx<SinkWriter> intoOut = () -> new CollectingSinkWriter("out");

        DagBindings bindings = new DagBindings(
                sourceId -> insertsSource(List.of(1, 2, 3, 4), "orders"),
                step -> keepEvenIds,
                syncElement -> intoOut,
                ref -> Map.of(
                        FromRef.literal("orders_src"), List.of("orders_src"),
                        FromRef.literal("keep_even"), List.of("keep_even")).getOrDefault(ref, List.of()));

        CollectingSinkWriter.reset("out");
        member.getJet().newJob(PipelineDagBuilder.build(pipeline, bindings)).join();

        assertThat(CollectingSinkWriter.collected("out")).containsExactlyInAnyOrder(2, 4);
    }

    @Test
    void built_union_dag_merges_two_sources_at_runtime() {
        PipelineResource pipeline = new PipelineResource(
                "p", null,
                List.of("a_src", "b_src"),
                List.of(Step.inline("u",
                        FromClause.list(FromRef.literal("a_src"), FromRef.literal("b_src")),
                        new TransformBody.Union(), null, null)),
                null,
                new ServeBlock.Inline(null, FromRef.literal("u"),
                        List.of(new SyncElement("sync_1", "orders_dest", null, null, null, null)),
                        null, null),
                null, null);

        SupplierEx<SinkWriter> intoOut = () -> new CollectingSinkWriter("out");

        DagBindings bindings = new DagBindings(
                sourceId -> insertsSource(
                        sourceId.equals("a_src") ? List.of(1, 2) : List.of(10, 20), sourceId),
                step -> {
                    throw new AssertionError("union must not consult transformPorts");
                },
                syncElement -> intoOut,
                ref -> Map.of(
                        FromRef.literal("a_src"), List.of("a_src"),
                        FromRef.literal("b_src"), List.of("b_src"),
                        FromRef.literal("u"), List.of("u")).getOrDefault(ref, List.of()));

        CollectingSinkWriter.reset("out");
        member.getJet().newJob(PipelineDagBuilder.build(pipeline, bindings)).join();

        assertThat(CollectingSinkWriter.collected("out")).containsExactlyInAnyOrder(1, 2, 10, 20);
    }

    /** A source that builds insert envelopes on the member from a serializable list of ids. */
    private static ProcessorMetaSupplier insertsSource(List<Integer> ids, String src) {
        return ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) () -> new InsertsSource(ids, src)));
    }

    private static final class InsertsSource extends AbstractProcessor {
        private final List<Integer> ids;
        private final String src;
        private int next;

        InsertsSource(List<Integer> ids, String src) {
            this.ids = ids;
            this.src = src;
        }

        @Override
        public boolean complete() {
            while (next < ids.size()) {
                int id = ids.get(next);
                if (!tryEmit(Envelope.insert(id, src, Map.of("id", id), null))) {
                    return false;
                }
                next++;
            }
            return true;
        }
    }
}
