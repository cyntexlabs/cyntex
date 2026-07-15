package io.cyntex.e2e;

import io.cyntex.core.lifecycle.LifecycleVerb;
import io.cyntex.core.lifecycle.PipelineState;

/**
 * How one tier reaches the product under test. The same specification runs on every binding, so
 * this is the whole fidelity axis: an in-process binding boots the product inside this JVM, a
 * real-process binding drives a shipped artifact. Nothing above this interface knows which.
 *
 * <p>Readings are deliberately taken from outside the product: {@link #count} reads the target
 * endpoint itself rather than any in-process record of what was written, so a specification asserts
 * what a user would see.
 */
public interface TierBinding {

    /** Registers a connector's runtime jar; idempotent by content hash. */
    void registerConnector(String connectorId);

    /** Applies one product resource file, by path relative to the specification. */
    void applyResource(String resourceFile);

    /** Discovers and persists a source model, feeding target-table creation. */
    void discoverSchema(String resourceId);

    /** Lays down initial rows on a table before the run begins. */
    void seed(TableAlias table, long rows);

    /** Records a lifecycle intent. Returns once the intent is recorded, not once it converges. */
    void drive(String pipelineId, LifecycleVerb verb);

    /** Produces changes against a table while the pipeline runs. */
    void cdc(TableAlias table, CdcOp op, int rows);

    /** Reads the current row count from the endpoint that owns the table. */
    long count(TableAlias table);

    /** Reads the published lifecycle state of a pipeline. */
    PipelineState state(String pipelineId);
}
