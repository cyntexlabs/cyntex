package io.cyntex.app;

import ch.qos.logback.classic.Logger;
import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.StateJson;
import io.cyntex.core.logging.LogLine;
import io.cyntex.core.logging.RingBufferLogSink;
import io.cyntex.runtime.scheduler.LifecycleActuator;
import io.cyntex.runtime.scheduler.ObservationPublisher;
import io.cyntex.runtime.scheduler.PipelineConverger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static io.cyntex.core.lifecycle.PipelineState.FAILED;
import static io.cyntex.core.lifecycle.PipelineState.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The convergence driver ticks the framework-free converger over every desired pipeline. It reconciles
 * each one toward its intent, and isolates a per-pipeline failure so one bad pipeline cannot starve the
 * rest of the pass.
 */
class ConvergenceDriverTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

    private final InMemoryDesiredStore desired = new InMemoryDesiredStore();
    private final InMemoryStateStore state = new InMemoryStateStore();
    private final InMemoryObservationStore observations = new InMemoryObservationStore();
    private final PipelineConverger converger =
            new PipelineConverger(desired, state, new NoOpActuator(), Clock.fixed(T0, ZoneOffset.UTC));
    private final ConvergenceDriver driver =
            new ConvergenceDriver(converger, desired, new ObservationPublisher(state, observations));

    @Test
    void reconcileConvergesEveryDesiredPipeline() {
        desired.save(new DesiredState("orders", RUNNING, "rev-1"));
        desired.save(new DesiredState("users", RUNNING, "rev-1"));

        driver.reconcile();

        assertThat(state.read("orders").orElseThrow().stateJson()).isEqualTo(StateJson.of(RUNNING));
        assertThat(state.read("users").orElseThrow().stateJson()).isEqualTo(StateJson.of(RUNNING));
        // Each converged pipeline also has its observation published for the read faces to serve.
        assertThat(observations.read("orders").orElseThrow().state()).isEqualTo(RUNNING);
        assertThat(observations.read("users").orElseThrow().state()).isEqualTo(RUNNING);
    }

    @Test
    void reconcileIsolatesAPerPipelineFailure() {
        desired.save(new DesiredState("broken", RUNNING, "rev-1"));
        desired.save(new DesiredState("healthy", RUNNING, "rev-1"));
        state.failFor("broken");

        driver.reconcile();

        // The healthy pipeline still converges even though the broken one threw mid-pass.
        assertThat(state.read("healthy").orElseThrow().stateJson()).isEqualTo(StateJson.of(RUNNING));
    }

    @Test
    void logsEmittedDuringAPipelinesPassAreAttributedToThatPipeline() {
        // Feed the node-local sink from the driver's own logger via the pipeline appender; make one pipeline
        // fail so the driver logs a warning during that pipeline's turn. The warning must land under that
        // pipeline id, proving the driver sets the pipeline attribution slot around each pipeline's work.
        RingBufferLogSink sink = new RingBufferLogSink(8, 8);
        Logger driverLogger = (Logger) LoggerFactory.getLogger(ConvergenceDriver.class);
        PipelineLogAppender appender = new PipelineLogAppender(sink);
        appender.setContext(driverLogger.getLoggerContext());
        appender.start();
        driverLogger.addAppender(appender);
        try {
            desired.save(new DesiredState("broken", RUNNING, "rev-1"));
            state.failFor("broken");

            driver.reconcile();
        } finally {
            driverLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(sink.tail("broken")).extracting(LogLine::level).containsExactly("WARN");
        // The attribution slot is cleared after the pass, so an unrelated later log is not misattributed.
        assertThat(MDC.get("pipeline_id")).isNull();
    }

    @Test
    void aPipelineWhoseJobDiedIsDrivenToFailedAndLogged() {
        // A job that dies on its own does not throw into the reconcile pass — the converge side moves the
        // pipeline to FAILED and returns it. The driver must log that, so a dead job is no longer the
        // silent "found 0" it used to be, and the observable state reflects the failure.
        RingBufferLogSink sink = new RingBufferLogSink(8, 8);
        Logger driverLogger = (Logger) LoggerFactory.getLogger(ConvergenceDriver.class);
        PipelineLogAppender appender = new PipelineLogAppender(sink);
        appender.setContext(driverLogger.getLoggerContext());
        appender.start();
        driverLogger.addAppender(appender);

        FailingActuator actuator = new FailingActuator();
        PipelineConverger converger =
                new PipelineConverger(desired, state, actuator, Clock.fixed(T0, ZoneOffset.UTC));
        ConvergenceDriver driver =
                new ConvergenceDriver(converger, desired, new ObservationPublisher(state, observations));
        try {
            desired.save(new DesiredState("orders", RUNNING, "rev-1"));
            driver.reconcile(); // orders -> RUNNING while the job is healthy
            actuator.failWith(new RuntimeException("sink write failed"));

            driver.reconcile(); // the job has died: orders -> FAILED, and the driver logs it
        } finally {
            driverLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(sink.tail("orders")).extracting(LogLine::level).contains("WARN");
        assertThat(state.read("orders").orElseThrow().stateJson()).isEqualTo(StateJson.of(FAILED));
    }

    /** A no-op actuator whose failure() a test can arm, to drive a pipeline to FAILED without a real job. */
    private static final class FailingActuator implements LifecycleActuator {
        private Throwable failure;

        void failWith(Throwable cause) {
            this.failure = cause;
        }

        @Override
        public void start(String pipelineId) {
        }

        @Override
        public void pause(String pipelineId) {
        }

        @Override
        public void resume(String pipelineId) {
        }

        @Override
        public void stop(String pipelineId) {
        }

        @Override
        public Optional<Throwable> failure(String pipelineId) {
            return Optional.ofNullable(failure);
        }
    }
}
