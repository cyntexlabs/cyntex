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
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import java.time.Duration;
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

    /** A one-vertex streaming DAG whose only processor never completes, so the job stays RUNNING. */
    private static DAG foreverDag() {
        DAG dag = new DAG();
        dag.newVertex("forever", ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) ForeverSource::new)));
        return dag;
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
