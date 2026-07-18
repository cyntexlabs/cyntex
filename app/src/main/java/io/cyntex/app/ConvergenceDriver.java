package io.cyntex.app;

import io.cyntex.runtime.scheduler.ConvergeResult;
import io.cyntex.runtime.scheduler.ConvergeStatus;
import io.cyntex.runtime.scheduler.ObservationPublisher;
import io.cyntex.runtime.scheduler.PipelineConverger;
import io.cyntex.spi.store.DesiredStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically reconciles every pipeline's actual state toward its desired intent, then publishes each
 * pipeline's observation for the read faces to serve. Spring's scheduler lives here in the assembly
 * layer, not in the runtime ring where the framework is banned, so this thin driver ticks the
 * framework-free {@link PipelineConverger} and {@link ObservationPublisher}. Each pipeline is handled
 * independently: a failure on one is logged and does not stop the pass, so one bad pipeline cannot
 * starve the rest.
 */
final class ConvergenceDriver {

    private static final Logger LOG = LoggerFactory.getLogger(ConvergenceDriver.class);

    private final PipelineConverger converger;
    private final DesiredStore desired;
    private final ObservationPublisher publisher;

    ConvergenceDriver(PipelineConverger converger, DesiredStore desired, ObservationPublisher publisher) {
        this.converger = converger;
        this.desired = desired;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${cyntex.converge.interval-ms:1000}")
    void reconcile() {
        for (String pipelineId : desired.pipelineIds()) {
            // Attribute every line logged while reconciling this pipeline to it, so the logs read face can
            // tail per pipeline. Cleared per pipeline so the slot never leaks onto the next one or an idle tick.
            MDC.put(PipelineLogAppender.PIPELINE_ID_MDC_KEY, pipelineId);
            try {
                ConvergeResult result = converger.converge(pipelineId);
                if (result.status() == ConvergeStatus.FAILED) {
                    // The job died on its own; the converge side moved the pipeline to the observable
                    // FAILED state. Log the cause so a dead job is not the silent "found 0" it used to be.
                    LOG.warn("Pipeline {} entered FAILED: its data-plane job died", pipelineId,
                            result.failure().orElse(null));
                }
                publisher.publish(pipelineId);
            } catch (RuntimeException e) {
                LOG.warn("Reconcile pass for pipeline {} failed; retrying on the next tick", pipelineId, e);
            } finally {
                MDC.remove(PipelineLogAppender.PIPELINE_ID_MDC_KEY);
            }
        }
    }
}
