package io.cyntex.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationTest {

    @Test
    void holdsThePipelineIdStateMetricsAndSnapshot() {
        Observation obs = new Observation(
                "orders_sync",
                PipelineState.RUNNING,
                Map.of("recordCount", 128500L, "lag", 42000L),
                Map.of("orders", new TableSnapshot(90000L, 120000L, 75)));

        assertThat(obs.pipelineId()).isEqualTo("orders_sync");
        assertThat(obs.state()).isEqualTo(PipelineState.RUNNING);
        assertThat(obs.metrics()).containsEntry("recordCount", 128500L).containsEntry("lag", 42000L);
        assertThat(obs.snapshot()).containsKey("orders");
        assertThat(obs.snapshot().get("orders").rowsDone()).isEqualTo(90000L);
    }

    @Test
    void requiresPipelineIdAndState() {
        assertThatNullPointerException()
                .isThrownBy(() -> new Observation(null, PipelineState.NEW, Map.of(), Map.of()));
        assertThatNullPointerException()
                .isThrownBy(() -> new Observation("p1", null, Map.of(), Map.of()));
    }

    @Test
    void nullMetricsOrSnapshotBecomeEmpty() {
        // A state-only observation is normal (metrics/snapshot sources may not be wired): null reads as
        // empty (unavailable), never a NullPointerException at a read site.
        Observation obs = new Observation("p1", PipelineState.NEW, null, null);

        assertThat(obs.metrics()).isEmpty();
        assertThat(obs.snapshot()).isEmpty();
    }

    @Test
    void metricsAndSnapshotAreDefensivelyCopied() {
        Map<String, Long> metrics = new HashMap<>(Map.of("recordCount", 1L));
        Map<String, TableSnapshot> snapshot = new HashMap<>(Map.of("t", new TableSnapshot(1L, null, null)));
        Observation obs = new Observation("p1", PipelineState.RUNNING, metrics, snapshot);

        metrics.put("recordCount", 999L);
        snapshot.clear();

        assertThat(obs.metrics()).containsEntry("recordCount", 1L);
        assertThat(obs.snapshot()).containsKey("t");
        assertThatThrownBy(() -> obs.metrics().put("x", 1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void tableSnapshotAllowsUnavailableTotalAndPct() {
        // rows_total not yet wired: total and pct are null (unavailable), never faked as 0 or 100.
        TableSnapshot unavailable = new TableSnapshot(500L, null, null);

        assertThat(unavailable.rowsDone()).isEqualTo(500L);
        assertThat(unavailable.rowsTotal()).isNull();
        assertThat(unavailable.donePct()).isNull();
    }
}
