package io.cyntex.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the Jet job lifecycle actuator against an embedded single member with a synthetic
 * never-ending job, so the lifecycle operations have a live streaming job to act on. Every job is
 * named by its pipeline id, so the actuator can find it again to suspend / resume / cancel; the real
 * source -> sink topology is built elsewhere (this test carries no SRS or connector dependency).
 */
class EngineTest {

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
        // Job metrics are read live off the member's last collection, so collect once a second rather
        // than the multi-second default to keep the record-count assertions prompt.
        config.getMetricsConfig().setCollectionFrequencySeconds(1);
        member = Hazelcast.newHazelcastInstance(config);
    }

    @AfterEach
    void stopMember() {
        if (member != null) {
            member.shutdown();
        }
    }

    @Test
    void submit_starts_the_job_under_the_pipeline_id_name() {
        Engine engine = new Engine(member);

        engine.submit("orders-pipe", foreverDag());

        Job job = member.getJet().getJob("orders-pipe");
        assertThat(job).isNotNull();
        awaitStatus(job, JobStatus.RUNNING);
    }

    @Test
    void suspend_pauses_a_running_job() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.RUNNING);

        engine.suspend("orders-pipe");

        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.SUSPENDED);
    }

    @Test
    void resume_restarts_a_suspended_job() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.RUNNING);
        engine.suspend("orders-pipe");
        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.SUSPENDED);

        engine.resume("orders-pipe");

        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.RUNNING);
    }

    @Test
    void cancel_terminates_a_running_job() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        Job job = member.getJet().getJob("orders-pipe");
        awaitStatus(job, JobStatus.RUNNING);

        engine.cancel("orders-pipe");

        awaitStatus(job, JobStatus.FAILED);
    }

    @Test
    void failureOf_reports_the_cause_of_a_job_that_died_on_its_own() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", failingDag());
        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.FAILED);

        assertThat(engine.failureOf("orders-pipe"))
                .get()
                .satisfies(cause -> assertThat(cause).hasStackTraceContaining("boom in the source"));
    }

    @Test
    void failureOf_is_empty_for_a_job_a_stop_cancelled_so_a_stop_is_not_a_failure() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        Job job = member.getJet().getJob("orders-pipe");
        awaitStatus(job, JobStatus.RUNNING);
        engine.cancel("orders-pipe");
        awaitStatus(job, JobStatus.FAILED); // Jet marks a cancelled job FAILED, but it did not fail on its own

        assertThat(engine.failureOf("orders-pipe")).isEmpty();
    }

    @Test
    void failureOf_is_empty_while_a_job_is_running_and_for_an_unknown_pipeline() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.RUNNING);

        assertThat(engine.failureOf("orders-pipe")).isEmpty();
        assertThat(engine.failureOf("ghost")).isEmpty();
    }

    @Test
    void submit_is_idempotent_and_does_not_start_a_second_job() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        Job first = member.getJet().getJob("orders-pipe");
        awaitStatus(first, JobStatus.RUNNING);

        engine.submit("orders-pipe", foreverDag());

        Job second = member.getJet().getJob("orders-pipe");
        assertThat(second.getId()).isEqualTo(first.getId());
        long named = member.getJet().getJobs().stream()
                .filter(j -> "orders-pipe".equals(j.getName()))
                .count();
        assertThat(named).isEqualTo(1);
    }

    @Test
    void suspend_of_an_unknown_pipeline_is_a_coded_error() {
        Engine engine = new Engine(member);

        assertThatThrownBy(() -> engine.suspend("ghost"))
                .isInstanceOf(io.cyntex.core.common.CyntexException.class)
                .satisfies(e -> {
                    io.cyntex.core.common.CyntexException coded = (io.cyntex.core.common.CyntexException) e;
                    assertThat(coded.code().code()).isEqualTo("engine.no-such-job");
                    assertThat(coded.args()).containsEntry("pipeline", "ghost");
                });
    }

    @Test
    void cancel_of_an_unknown_pipeline_is_a_noop() {
        Engine engine = new Engine(member);

        engine.cancel("ghost");

        assertThat(member.getJet().getJob("ghost")).isNull();
    }

    @Test
    void resume_of_an_unknown_pipeline_is_a_coded_error() {
        Engine engine = new Engine(member);

        assertThatThrownBy(() -> engine.resume("ghost"))
                .isInstanceOf(io.cyntex.core.common.CyntexException.class)
                .satisfies(e -> assertThat(((io.cyntex.core.common.CyntexException) e).code().code())
                        .isEqualTo("engine.no-such-job"));
    }

    @Test
    void submitted_jobs_carry_no_snapshot_so_jet_is_not_the_source_of_truth() {
        Engine engine = new Engine(member);

        engine.submit("orders-pipe", foreverDag());

        assertThat(member.getJet().getJob("orders-pipe").getConfig().getProcessingGuarantee())
                .isEqualTo(com.hazelcast.jet.config.ProcessingGuarantee.NONE);
    }

    @Test
    void suspend_of_a_stopped_pipeline_is_a_coded_error_not_a_bare_crash() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        Job job = member.getJet().getJob("orders-pipe");
        awaitStatus(job, JobStatus.RUNNING);
        engine.cancel("orders-pipe");
        awaitStatus(job, JobStatus.FAILED); // Jet retains the terminal job under its name

        assertThatThrownBy(() -> engine.suspend("orders-pipe"))
                .isInstanceOf(io.cyntex.core.common.CyntexException.class)
                .satisfies(e -> assertThat(((io.cyntex.core.common.CyntexException) e).code().code())
                        .isEqualTo("engine.no-such-job"));
    }

    @Test
    void resume_of_a_stopped_pipeline_is_a_coded_error_not_a_bare_crash() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", foreverDag());
        Job job = member.getJet().getJob("orders-pipe");
        awaitStatus(job, JobStatus.RUNNING);
        engine.cancel("orders-pipe");
        awaitStatus(job, JobStatus.FAILED);

        assertThatThrownBy(() -> engine.resume("orders-pipe"))
                .isInstanceOf(io.cyntex.core.common.CyntexException.class)
                .satisfies(e -> assertThat(((io.cyntex.core.common.CyntexException) e).code().code())
                        .isEqualTo("engine.no-such-job"));
    }

    @Test
    void recordCount_counts_the_records_that_reached_the_serve_sink() {
        Engine engine = new Engine(member);
        engine.submit("orders-pipe", countingDag(3));
        awaitStatus(member.getJet().getJob("orders-pipe"), JobStatus.RUNNING);

        awaitRecordCount(engine, "orders-pipe", 3);
    }

    @Test
    void recordCount_is_empty_for_a_pipeline_with_no_live_job() {
        Engine engine = new Engine(member);
        // An unknown pipeline has no job at all.
        assertThat(engine.recordCount("ghost")).isEmpty();

        // A cancelled job is terminal, so it is retained under its name but is not live.
        engine.submit("orders-pipe", foreverDag());
        Job job = member.getJet().getJob("orders-pipe");
        awaitStatus(job, JobStatus.RUNNING);
        engine.cancel("orders-pipe");
        awaitStatus(job, JobStatus.FAILED);
        assertThat(engine.recordCount("orders-pipe")).isEmpty();
    }

    /**
     * A three-vertex streaming DAG: a source that emits {@code total} rows then idles forever (so the
     * job stays RUNNING and its metrics stay readable), through a passthrough transform, into a
     * draining serve sink. The transform vertex receives the same rows as the sink, so its presence
     * makes the serve-sink filter load-bearing: counting every vertex's received rows would double
     * the total, and only the {@code serve.} prefix the real builder stamps on serve sinks isolates
     * the records that actually reached a sink.
     */
    private static DAG countingDag(int total) {
        DAG dag = new DAG();
        Vertex source = dag.newVertex("src", ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) () -> new EmitThenIdleSource(total))));
        Vertex transform = dag.newVertex("xform", ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) Passthrough::new)));
        Vertex sink = dag.newVertex("serve.out", ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) DrainingSink::new)));
        dag.edge(Edge.between(source, transform));
        dag.edge(Edge.between(transform, sink));
        return dag;
    }

    /** Re-emits every row it receives, so the transform vertex also carries a received count. */
    private static final class Passthrough extends AbstractProcessor {
        @Override
        public boolean isCooperative() {
            return false;
        }

        @Override
        protected boolean tryProcess(int ordinal, Object item) {
            return tryEmit(item);
        }
    }

    /** Emits a fixed number of rows once, then idles forever so the streaming job stays RUNNING. */
    private static final class EmitThenIdleSource extends AbstractProcessor {
        private final int total;
        private int emitted;

        private EmitThenIdleSource(int total) {
            this.total = total;
        }

        @Override
        public boolean isCooperative() {
            return false;
        }

        @Override
        public boolean complete() {
            while (emitted < total) {
                if (!tryEmit("row-" + emitted)) {
                    return false;
                }
                emitted++;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
            return false;
        }
    }

    /** Consumes every row it receives, so the source's rows all reach it and are counted. */
    private static final class DrainingSink extends AbstractProcessor {
        @Override
        public boolean isCooperative() {
            return false;
        }

        @Override
        protected boolean tryProcess(int ordinal, Object item) {
            return true;
        }
    }

    /** Polls record count until it reaches the expected value, failing if it does not within budget. */
    private static void awaitRecordCount(Engine engine, String pipelineId, long expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        OptionalLong last = OptionalLong.empty();
        while (System.nanoTime() < deadline) {
            last = engine.recordCount(pipelineId);
            if (last.isPresent() && last.getAsLong() == expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("record count did not reach " + expected + " within budget; last was " + last);
    }

    /** A one-vertex streaming DAG whose only processor never completes, so the job stays RUNNING. */
    private static DAG foreverDag() {
        DAG dag = new DAG();
        dag.newVertex("forever", ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) ForeverSource::new)));
        return dag;
    }

    /** A one-vertex DAG whose only processor throws, so the job fails on its own with that cause. */
    private static DAG failingDag() {
        DAG dag = new DAG();
        dag.newVertex("boom", ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) FailingSource::new)));
        return dag;
    }

    /** Throws on its first run, so the job it belongs to fails on its own rather than being cancelled. */
    private static final class FailingSource extends AbstractProcessor {
        @Override
        public boolean isCooperative() {
            return false;
        }

        @Override
        public boolean complete() {
            throw new RuntimeException("boom in the source");
        }
    }

    /** Emits nothing and never signals completion; a stand-in for an unbounded source. */
    private static final class ForeverSource extends AbstractProcessor {
        @Override
        public boolean isCooperative() {
            return false;
        }

        @Override
        public boolean complete() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
            return false;
        }
    }

    /** Polls the job until it reaches the expected status, failing if it does not within the budget. */
    private static void awaitStatus(Job job, JobStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JobStatus last = null;
        while (System.nanoTime() < deadline) {
            last = job.getStatus();
            if (last == expected) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("job did not reach " + expected + " within budget; last status was " + last);
    }
}
