package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.CheckpointDoc;
import io.cyntex.core.lifecycle.DesiredState;
import io.cyntex.core.lifecycle.StateJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.cyntex.core.lifecycle.PipelineState.COMPLETED;
import static io.cyntex.core.lifecycle.PipelineState.NEW;
import static io.cyntex.core.lifecycle.PipelineState.PAUSED;
import static io.cyntex.core.lifecycle.PipelineState.RUNNING;
import static io.cyntex.core.lifecycle.PipelineState.STOPPED;
import static io.cyntex.runtime.scheduler.ConvergeStatus.CONVERGED;
import static io.cyntex.runtime.scheduler.ConvergeStatus.NOTHING_TO_DO;
import static io.cyntex.runtime.scheduler.ConvergeStatus.SUPERSEDED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The convergence loop: it reads the desired intent, seeds the actual checkpoint when a pipeline
 * first appears, and drives the actual state toward the target through the fencing compare-and-swap,
 * rebasing on a fenced write. The artificial-failover cases stage a competing writer to prove the
 * single-node fencing contract — strictly-monotonic epoch, one winner per race — that a real
 * multi-node failover would otherwise be needed to witness.
 */
class PipelineConvergerTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final String REV = "rev-1";

    private final InMemoryDesiredStore desired = new InMemoryDesiredStore();
    private final InMemoryStateStore state = new InMemoryStateStore();
    private final PipelineConverger converger =
            new PipelineConverger(desired, state, Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    @DisplayName("with no desired intent there is nothing to converge and no checkpoint is written")
    void noDesiredIsANoOp() {
        ConvergeResult result = converger.converge("p1");

        assertThat(result.status()).isEqualTo(NOTHING_TO_DO);
        assertThat(state.read("p1")).isEmpty();
    }

    @Test
    @DisplayName("a first convergence seeds the checkpoint and drives it to the desired state")
    void firstConvergenceSeedsAndDrives() {
        desired.save(new DesiredState("p1", RUNNING, REV));

        ConvergeResult result = converger.converge("p1");

        assertThat(result.status()).isEqualTo(CONVERGED);
        CheckpointDoc actual = state.read("p1").orElseThrow();
        assertThat(actual.stateJson()).isEqualTo(StateJson.of(RUNNING));
        assertThat(actual.epoch()).isEqualTo(1); // 0 = NEW seed, 1 = first transition
    }

    @Test
    @DisplayName("converging an already-converged pipeline writes nothing and leaves the epoch alone")
    void convergenceIsIdempotent() {
        desired.save(new DesiredState("p1", RUNNING, REV));
        converger.converge("p1");
        long epochAfterFirst = state.read("p1").orElseThrow().epoch();

        ConvergeResult again = converger.converge("p1");

        assertThat(again.status()).isEqualTo(CONVERGED);
        assertThat(state.read("p1").orElseThrow().epoch()).isEqualTo(epochAfterFirst);
    }

    @Test
    @DisplayName("the epoch advances strictly monotonically across a start/pause/resume/stop sequence")
    void epochIsMonotonicAcrossTheVerbSequence() {
        converge(RUNNING);
        converge(PAUSED);
        converge(RUNNING);
        converge(STOPPED);

        CheckpointDoc actual = state.read("p1").orElseThrow();
        assertThat(actual.stateJson()).isEqualTo(StateJson.of(STOPPED));
        assertThat(actual.epoch()).isEqualTo(4); // seed 0 -> RUNNING 1 -> PAUSED 2 -> RUNNING 3 -> STOPPED 4
    }

    @Test
    @DisplayName("a fenced converger re-reads the fresh epoch and its retry wins; the epoch advances once per write")
    void artificialFailoverRebasesAndWins() {
        state.create("p1", StateJson.of(NEW), T0); // seed at epoch 0
        desired.save(new DesiredState("p1", RUNNING, REV));

        // A competing owner writes PAUSED at epoch 0 just before the converger's first swap, fencing it.
        AtomicBoolean fired = new AtomicBoolean(false);
        state.onBeforeSwap(() -> {
            if (fired.compareAndSet(false, true)) {
                state.applySwap("p1", 0L, StateJson.of(PAUSED), T0);
            }
        });

        ConvergeResult result = converger.converge("p1");

        assertThat(result.status()).isEqualTo(CONVERGED);
        CheckpointDoc actual = state.read("p1").orElseThrow();
        assertThat(actual.stateJson()).isEqualTo(StateJson.of(RUNNING));
        assertThat(actual.epoch()).isEqualTo(2); // 0 seed -> 1 competitor(PAUSED) -> 2 converger(RUNNING)
        assertThat(state.swapAttempts()).isEqualTo(2); // one fenced, one applied
    }

    @Test
    @DisplayName("a relentlessly fenced converger gives up after a bounded number of retries rather than spinning")
    void boundedRetriesGiveUpWhenPersistentlyFenced() {
        state.create("p1", StateJson.of(NEW), T0);
        desired.save(new DesiredState("p1", RUNNING, REV));

        // A competitor bumps the epoch before every converger swap, so the converger is always stale.
        state.onBeforeSwap(() -> {
            long current = state.read("p1").orElseThrow().epoch();
            state.applySwap("p1", current, StateJson.of(PAUSED), T0);
        });

        ConvergeResult result = converger.converge("p1");

        assertThat(result.status()).isEqualTo(SUPERSEDED);
        assertThat(state.swapAttempts()).isEqualTo(PipelineConverger.MAX_CAS_ATTEMPTS);
    }

    @Test
    @DisplayName("markCompleted drives a running pipeline to the terminal COMPLETED state")
    void markCompletedDrivesToCompleted() {
        converge(RUNNING); // actual now RUNNING

        ConvergeResult result = converger.markCompleted("p1");

        assertThat(result.status()).isEqualTo(CONVERGED);
        assertThat(state.read("p1").orElseThrow().stateJson()).isEqualTo(StateJson.of(COMPLETED));
    }

    @Test
    @DisplayName("markCompleted on a pipeline that has never run is a no-op")
    void markCompletedWithoutACheckpointIsANoOp() {
        ConvergeResult result = converger.markCompleted("p1");

        assertThat(result.status()).isEqualTo(NOTHING_TO_DO);
        assertThat(state.read("p1")).isEmpty();
    }

    private void converge(io.cyntex.core.lifecycle.PipelineState target) {
        desired.save(new DesiredState("p1", target, REV));
        converger.converge("p1");
    }
}
