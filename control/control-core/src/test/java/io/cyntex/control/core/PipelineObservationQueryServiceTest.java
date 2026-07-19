package io.cyntex.control.core;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.lifecycle.Observation;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.lifecycle.TableSnapshot;
import io.cyntex.spi.store.ObservationStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The pipeline observation read side: the three store-backed read faces (status / metrics / snapshot)
 * each project the latest published observation for a pipeline, and a read of a pipeline that has
 * published no observation is a coded {@code monitor.no-observation} diagnostic — never a bare crash,
 * so the same read serves a frontend with no stderr/exit channel.
 */
class PipelineObservationQueryServiceTest {

    private static ObservationStore storeWith(Observation... published) {
        Map<String, Observation> map = new HashMap<>();
        for (Observation o : published) {
            map.put(o.pipelineId(), o);
        }
        return new ObservationStore() {
            @Override
            public void save(Observation observation) {
                map.put(observation.pipelineId(), observation);
            }

            @Override
            public Optional<Observation> read(String pipelineId) {
                return Optional.ofNullable(map.get(pipelineId));
            }
        };
    }

    private static Observation running() {
        return new Observation("orders_sync", PipelineState.RUNNING,
                Map.of("recordCount", 5L), Map.of("orders", new TableSnapshot(10L, 20L, 50)));
    }

    private static Observation runningWithPositions() {
        return new Observation("orders_sync", PipelineState.RUNNING,
                Map.of("recordCount", 5L), Map.of("orders", new TableSnapshot(10L, 20L, 50)),
                Map.of("orders", "w7"));
    }

    @Test
    void statusProjectsThePublishedState() {
        var service = new PipelineObservationQueryService(storeWith(running()));

        PipelineStatus status = service.status("orders_sync");

        assertThat(status.pipelineId()).isEqualTo("orders_sync");
        assertThat(status.state()).isEqualTo(PipelineState.RUNNING);
    }

    @Test
    void metricsProjectsThePublishedMetricMap() {
        var service = new PipelineObservationQueryService(storeWith(running()));

        assertThat(service.metrics("orders_sync").metrics()).containsEntry("recordCount", 5L);
    }

    @Test
    void metricsProjectsThePublishedPositions() {
        var service = new PipelineObservationQueryService(storeWith(runningWithPositions()));

        assertThat(service.metrics("orders_sync").positions()).containsEntry("orders", "w7");
    }

    @Test
    void metricsPositionsAreEmptyWhenTheObservationHasNone() {
        var service = new PipelineObservationQueryService(storeWith(running()));

        assertThat(service.metrics("orders_sync").positions()).isEmpty();
    }

    @Test
    void snapshotProjectsThePublishedSnapshotMap() {
        var service = new PipelineObservationQueryService(storeWith(running()));

        assertThat(service.snapshot("orders_sync").snapshot().get("orders").rowsDone()).isEqualTo(10L);
    }

    @Test
    void statusOfAPipelineWithNoObservationIsNoObservationCoded() {
        var service = new PipelineObservationQueryService(storeWith());

        CyntexException thrown = catchThrowableOfType(
                () -> service.status("missing"), CyntexException.class);

        assertThat(thrown.code()).isEqualTo(MonitorError.NO_OBSERVATION);
        assertThat(thrown.args()).containsEntry("pipeline", "missing");
    }

    @Test
    void metricsOfAPipelineWithNoObservationIsNoObservationCoded() {
        var service = new PipelineObservationQueryService(storeWith());

        assertThatThrownBy(() -> service.metrics("missing"))
                .isInstanceOfSatisfying(CyntexException.class,
                        e -> assertThat(e.code()).isEqualTo(MonitorError.NO_OBSERVATION));
    }

    @Test
    void snapshotOfAPipelineWithNoObservationIsNoObservationCoded() {
        var service = new PipelineObservationQueryService(storeWith());

        assertThatThrownBy(() -> service.snapshot("missing"))
                .isInstanceOfSatisfying(CyntexException.class,
                        e -> assertThat(e.code()).isEqualTo(MonitorError.NO_OBSERVATION));
    }
}
