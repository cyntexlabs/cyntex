package io.cyntex.control.restapi;

import io.cyntex.control.core.PipelineMetrics;
import io.cyntex.control.core.PipelineObservationQueryService;
import io.cyntex.control.core.PipelineSnapshot;
import io.cyntex.control.core.PipelineStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three store-backed observation read faces projected onto HTTP: status / metrics / snapshot, each a
 * {@code GET} on a pipeline instance ({@code GET /api/pipelines/{id}/status}). Each handler is a thin
 * pass-through to the control-core query service — it names the pipeline, reads its latest published
 * observation, and returns the projection — and carries no business logic of its own. The reads mutate
 * nothing, so unlike the lifecycle write verbs they are unaudited and name no caller principal; the
 * interceptor still authenticates and grade-checks them like every other verb.
 *
 * <p>A read of a pipeline that has published no observation is not a bare 404: the query service raises the
 * coded {@code monitor.no-observation} diagnostic, which the shared advice renders as a structured 404 body,
 * so the same read serves a frontend with no stderr/exit channel.
 */
@RestController
class PipelineObservationController {

    private final PipelineObservationQueryService observations;

    PipelineObservationController(PipelineObservationQueryService observations) {
        this.observations = observations;
    }

    @Verb("pipeline.status")
    @GetMapping("/pipelines/{id}/status")
    PipelineStatus status(@PathVariable("id") String id) {
        return observations.status(id);
    }

    @Verb("pipeline.metrics")
    @GetMapping("/pipelines/{id}/metrics")
    PipelineMetrics metrics(@PathVariable("id") String id) {
        return observations.metrics(id);
    }

    @Verb("pipeline.snapshot")
    @GetMapping("/pipelines/{id}/snapshot")
    PipelineSnapshot snapshot(@PathVariable("id") String id) {
        return observations.snapshot(id);
    }
}
