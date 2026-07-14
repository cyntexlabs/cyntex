package io.cyntex.app;

import com.hazelcast.jet.core.DAG;

/**
 * Supplies the Jet topology a pipeline runs. The actuator asks for a pipeline's topology when it starts
 * the pipeline's job. Kept a seam so the topology a pipeline runs can vary — the store-backed builder in
 * production, an idle stand-in in a lifecycle test — without the actuator, which drives the job by
 * pipeline id alone, having to change.
 */
@FunctionalInterface
interface DagSource {

    /** The topology to run for {@code pipelineId}. */
    DAG dagFor(String pipelineId);
}
