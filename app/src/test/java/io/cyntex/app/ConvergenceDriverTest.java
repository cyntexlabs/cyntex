package io.cyntex.app;

import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.StateJson;
import io.cyntex.runtime.scheduler.ObservationPublisher;
import io.cyntex.runtime.scheduler.PipelineConverger;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
            new PipelineConverger(desired, state, Clock.fixed(T0, ZoneOffset.UTC));
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
}
