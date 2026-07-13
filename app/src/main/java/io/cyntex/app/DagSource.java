package io.cyntex.app;

import com.hazelcast.jet.core.DAG;

/**
 * Supplies the Jet topology a pipeline runs. The actuator asks for a pipeline's topology when it starts
 * the pipeline's job. Kept a seam so the placeholder topology this slice runs is swapped for the real
 * source -> sink builder when the capture and transform planes merge, without touching the actuator.
 */
@FunctionalInterface
interface DagSource {

    /** The topology to run for {@code pipelineId}. */
    DAG dagFor(String pipelineId);
}
