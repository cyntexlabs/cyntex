package io.cyntex.runtime.engine;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.core.DAG;
import io.cyntex.core.common.CyntexException;
import java.util.Map;
import java.util.Objects;

/**
 * The data-plane execution engine: the lifecycle actuator that maps a pipeline's lifecycle to Jet
 * job operations. A pipeline runs as exactly one Jet job named by the pipeline id, so the actuator
 * can find that job again to suspend / resume / cancel it. The topology (the {@link DAG}) is built
 * elsewhere and handed in; this engine only submits and controls the job.
 *
 * <p>Jet is a subordinate execution layer, not the source of truth: jobs carry no durable offset
 * snapshot ({@link ProcessingGuarantee#NONE}) and may be discarded and re-submitted at any time.
 * Continuation truth lives in the store, so a resumed job re-reads its start position from there
 * rather than from a Jet snapshot. Rule R4: this depends on the kernel and Hazelcast only.
 */
public final class Engine {

    private final HazelcastInstance member;

    public Engine(HazelcastInstance member) {
        this.member = Objects.requireNonNull(member, "member");
    }

    /**
     * Submits the pipeline's topology as a Jet job named by the pipeline id. Idempotent: if a job
     * under that name is already active it is left running and returned, so a repeated convergence
     * pass does not start a second job for the same pipeline.
     */
    public void submit(String pipelineId, DAG dag) {
        JobConfig config = new JobConfig()
                .setName(pipelineId)
                .setProcessingGuarantee(ProcessingGuarantee.NONE);
        member.getJet().newJobIfAbsent(dag, config);
    }

    /** Pauses the pipeline's running job. The job is kept so it can be resumed. */
    public void suspend(String pipelineId) {
        requireJob(pipelineId).suspend();
    }

    /**
     * Resumes the pipeline's suspended job. Under the no-snapshot guarantee this re-runs the
     * topology, so the source re-reads its start position from the store rather than a Jet snapshot.
     */
    public void resume(String pipelineId) {
        requireJob(pipelineId).resume();
    }

    /**
     * Cancels the pipeline's job. Idempotent: a pipeline with no running job is already stopped, so
     * this is a no-op. The pipeline-private continuation the store holds (its consumer cursor,
     * private operator state and sink watermark) is cleared separately when the source store is
     * present; this actuator owns the job side.
     */
    public void cancel(String pipelineId) {
        Job job = liveJob(pipelineId);
        if (job != null) {
            job.cancel();
        }
    }

    /**
     * The pipeline's live job, or {@code null} when it has none. A completed or cancelled job is
     * kept under its name but is not live: Jet retains terminal jobs, so a bare presence check would
     * mistake a stopped pipeline for a running one.
     */
    private Job liveJob(String pipelineId) {
        Job job = member.getJet().getJob(pipelineId);
        return job == null || job.getStatus().isTerminal() ? null : job;
    }

    /** The pipeline's live job, or a coded {@code engine.no-such-job} when it has none to act on. */
    private Job requireJob(String pipelineId) {
        Job job = liveJob(pipelineId);
        if (job == null) {
            throw new CyntexException(EngineError.NO_SUCH_JOB, Map.of("pipeline", pipelineId), null);
        }
        return job;
    }
}
