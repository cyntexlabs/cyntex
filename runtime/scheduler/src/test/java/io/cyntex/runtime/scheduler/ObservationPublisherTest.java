package io.cyntex.runtime.scheduler;

import io.cyntex.core.lifecycle.CasOutcome;
import io.cyntex.core.lifecycle.CheckpointDoc;
import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.lifecycle.StateJson;
import io.cyntex.spi.store.ObservationStore;
import io.cyntex.spi.store.StateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime observation publisher reads a pipeline's converged actual state and writes it out as the
 * pipeline's latest observation, so the control read faces have a store-backed projection to read. L1
 * publishes the state only (metrics and snapshot are empty until their sources are wired), and a
 * pipeline with no checkpoint yet is left unobserved rather than published as an empty doc.
 */
class ObservationPublisherTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

    private final MutableStateStore state = new MutableStateStore();
    private final RecordingObservationStore observations = new RecordingObservationStore();
    private final ObservationPublisher publisher = new ObservationPublisher(state, observations);

    @Test
    void publishesTheActualStateAsAnObservation() {
        state.seed("orders", PipelineState.RUNNING);

        publisher.publish("orders");

        Observation published = observations.read("orders").orElseThrow();
        assertThat(published.pipelineId()).isEqualTo("orders");
        assertThat(published.state()).isEqualTo(PipelineState.RUNNING);
        // L1 has no metric or snapshot source wired yet: they are published empty (unavailable), not faked.
        assertThat(published.metrics()).isEmpty();
        assertThat(published.snapshot()).isEmpty();
    }

    @Test
    void publishOfAPipelineWithNoCheckpointWritesNothing() {
        publisher.publish("never-run");

        assertThat(observations.read("never-run")).isEmpty();
    }

    @Test
    void republishOverwritesTheLatestProjection() {
        state.seed("orders", PipelineState.RUNNING);
        publisher.publish("orders");

        state.seed("orders", PipelineState.PAUSED);
        publisher.publish("orders");

        assertThat(observations.read("orders").orElseThrow().state()).isEqualTo(PipelineState.PAUSED);
    }

    /** In-memory state store double: seedable checkpoints, read-only for what the publisher needs. */
    private static final class MutableStateStore implements StateStore {

        private final Map<String, CheckpointDoc> docs = new HashMap<>();

        void seed(String pipelineId, PipelineState state) {
            docs.put(pipelineId, CheckpointDoc.initial(pipelineId, StateJson.of(state), T0));
        }

        @Override
        public Optional<CheckpointDoc> read(String pipelineId) {
            return Optional.ofNullable(docs.get(pipelineId));
        }

        @Override
        public void create(String pipelineId, String stateJson, Instant touchTime) {
            throw new UnsupportedOperationException("not exercised by the publisher");
        }

        @Override
        public CasOutcome compareAndSwap(String pipelineId, long expectedEpoch, String nextStateJson, Instant touchTime) {
            throw new UnsupportedOperationException("not exercised by the publisher");
        }
    }

    /** In-memory observation store double. */
    private static final class RecordingObservationStore implements ObservationStore {

        private final Map<String, Observation> docs = new HashMap<>();

        @Override
        public void save(Observation observation) {
            docs.put(observation.pipelineId(), observation);
        }

        @Override
        public Optional<Observation> read(String pipelineId) {
            return Optional.ofNullable(docs.get(pipelineId));
        }
    }
}
