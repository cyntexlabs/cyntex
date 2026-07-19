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
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * The runtime observation publisher reads a pipeline's converged actual state and writes it out as the
 * pipeline's latest observation, so the control read faces have a store-backed projection to read. L1
 * wires the errorCount metric from the actual state (0 healthy, 1 when FAILED); the remaining metrics are
 * absent and the snapshot dataset is published empty (no source yet). A pipeline with no checkpoint yet is
 * left unobserved rather than published as an empty doc.
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
        // errorCount is wired from the actual state: a healthy pipeline reports zero errors. The snapshot
        // source is not wired yet, so it is published empty (unavailable), not faked. This publisher was
        // built with no metric or position source, so recordCount is absent and positions are empty.
        assertThat(published.metrics()).containsOnly(entry("errorCount", 0L));
        assertThat(published.snapshot()).isEmpty();
        assertThat(published.positions()).isEmpty();
    }

    @Test
    void publishWiresTheRecordCountFromItsSourceIntoTheMetrics() {
        state.seed("orders", PipelineState.RUNNING);
        ObservationPublisher wired = new ObservationPublisher(
                state, observations, id -> OptionalLong.of(128L), id -> Map.of());

        wired.publish("orders");

        // recordCount rides the numeric metrics map alongside the always-present errorCount gauge.
        assertThat(observations.read("orders").orElseThrow().metrics())
                .containsOnly(entry("errorCount", 0L), entry("recordCount", 128L));
    }

    @Test
    void recordCountIsAbsentFromTheMetricsWhenItsSourceHasNoLiveJob() {
        state.seed("orders", PipelineState.RUNNING);
        ObservationPublisher wired = new ObservationPublisher(
                state, observations, id -> OptionalLong.empty(), id -> Map.of());

        wired.publish("orders");

        // A missing metric means the source is not wired (here: no live job), expressed by its absence
        // rather than a zero sentinel, so only the errorCount gauge is carried.
        assertThat(observations.read("orders").orElseThrow().metrics()).containsOnly(entry("errorCount", 0L));
    }

    @Test
    void publishWiresThePerTableSinkAckedPositionsFromItsSource() {
        state.seed("orders", PipelineState.RUNNING);
        ObservationPublisher wired = new ObservationPublisher(
                state, observations, id -> OptionalLong.empty(), id -> Map.of("orders", "gtid:aaa-1:100"));

        wired.publish("orders");

        // The durable sink-acked source position rides the positions map, keyed by table, as a String.
        assertThat(observations.read("orders").orElseThrow().positions())
                .containsOnly(entry("orders", "gtid:aaa-1:100"));
    }

    @Test
    void publishesAnErrorCountOfOneWhenThePipelineHasFailed() {
        state.seed("orders", PipelineState.FAILED);

        publisher.publish("orders");

        Observation published = observations.read("orders").orElseThrow();
        assertThat(published.state()).isEqualTo(PipelineState.FAILED);
        // A dead data-plane job is one observable error; every other state reports zero.
        assertThat(published.metrics()).containsOnly(entry("errorCount", 1L));
    }

    @Test
    void errorCountDropsBackToZeroWhenAFailedPipelineRecovers() {
        state.seed("orders", PipelineState.FAILED);
        publisher.publish("orders");
        assertThat(observations.read("orders").orElseThrow().metrics()).containsOnly(entry("errorCount", 1L));

        // Recovery goes through STOPPED (stop -> start); the gauge tracks the current state, not a running
        // total, so a non-FAILED state reports zero rather than accumulating the earlier failure.
        state.seed("orders", PipelineState.STOPPED);
        publisher.publish("orders");

        assertThat(observations.read("orders").orElseThrow().metrics()).containsOnly(entry("errorCount", 0L));
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

    @Test
    void publishReconcileFailureRecordsTheCountAgainstNewWhenNothingHasBeenObservedYet() {
        publisher.publishReconcileFailure("orders", 3L);

        Observation published = observations.read("orders").orElseThrow();
        // A pipeline that never converged witnessed no lifecycle state, so the projection is NEW rather than a
        // fabricated FAILED; the consecutive-failure count is the observable error signal.
        assertThat(published.state()).isEqualTo(PipelineState.NEW);
        assertThat(published.metrics()).containsOnly(entry("errorCount", 3L));
        assertThat(published.snapshot()).isEmpty();
    }

    @Test
    void publishReconcileFailurePreservesTheLastObservedStateAndCarriesTheCount() {
        state.seed("orders", PipelineState.RUNNING);
        publisher.publish("orders"); // the last state actually observed is RUNNING

        publisher.publishReconcileFailure("orders", 2L);

        Observation published = observations.read("orders").orElseThrow();
        // The last observed state is kept, not overwritten with FAILED — only the error count moves.
        assertThat(published.state()).isEqualTo(PipelineState.RUNNING);
        assertThat(published.metrics()).containsOnly(entry("errorCount", 2L));
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
