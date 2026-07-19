package io.cyntex.app;

import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;

/**
 * A stand-in topology for the lifecycle tests: every pipeline gets the same one-vertex streaming DAG whose
 * only processor emits nothing and never completes, so the job stays RUNNING until it is paused or stopped.
 * It exercises the actuator-to-engine binding and the converge loop without a store-backed pipeline, which
 * those tests do not want to set up; the store-backed builder is what production runs.
 */
final class IdleDagSource implements DagSource {

    @Override
    public DAG dagFor(String pipelineId) {
        DAG dag = new DAG();
        dag.newVertex("idle", ProcessorMetaSupplier.forceTotalParallelismOne(
                ProcessorSupplier.of((SupplierEx<Processor>) IdleSource::new)));
        return dag;
    }

    /** Emits nothing and never signals completion, so the job it runs stays RUNNING until acted on. */
    private static final class IdleSource extends AbstractProcessor {

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
}
