package io.cyntex.e2e;

import io.cyntex.control.core.MonitorError;
import io.cyntex.core.common.JsonWriter;
import io.cyntex.core.lifecycle.LifecycleError;
import io.cyntex.core.lifecycle.PipelineState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the harness is allowed to read into a status answer.
 *
 * <p>"This pipeline has published no observation yet" is the one refusal a wait must sit through; every
 * other refusal has to stay loud. The whole of the wait model rests on telling those apart, so the rule is
 * pinned here directly rather than only through a live server - the answers that must not be confused are
 * the ones a running product is least willing to produce on demand.
 */
class ControlPlaneTest {

    private static final String PIPELINE = "mongo2mongo";

    @Test
    void readsThePublishedState() {
        assertThat(ControlPlane.interpretState(200, status("RUNNING"), PIPELINE)).contains(PipelineState.RUNNING);
    }

    @Test
    void readsTheProductsOwnNoObservationCodeAsNothingPublishedYet() {
        assertThat(ControlPlane.interpretState(404, coded(MonitorError.NO_OBSERVATION.code()), PIPELINE))
                .isEmpty();
    }

    /**
     * The rule is written on the code and not on the status, so a 404 that means something else stays loud.
     * The status read serves only this one code today, so no live server can produce the answer below - the
     * rule is pinned here against the day another one is added, which is exactly when a status-only reading
     * would start passing a real failure off as a pipeline that is merely slow.
     */
    @Test
    void keepsAnotherCodesNotFoundLoud() {
        // Asserted on the status this reports, not on the code: the message quotes the body back verbatim, so
        // a code the test itself planted there would be echoed by an implementation that never read it.
        assertThatThrownBy(
                        () ->
                                ControlPlane.interpretState(
                                        404, coded(LifecycleError.UNKNOWN_PIPELINE.code()), PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("got 404");
    }

    @Test
    void keepsAServerFailureLoud() {
        assertThatThrownBy(() -> ControlPlane.interpretState(500, "boom", PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("got 500");
    }

    /**
     * A refusal that is not the product's structured body at all - an empty answer, or a proxy's HTML. The
     * caller has to hear the status and the body it actually got; reading the body for a code must not throw
     * a parse error over the top of the diagnostic, which is the one thing this answer is good for.
     */
    @Test
    void keepsARefusalThatCarriesNoStructuredBodyLoud() {
        for (String body : List.of("", "<html><body>Not Found</body></html>", "boom")) {
            assertThatThrownBy(() -> ControlPlane.interpretState(404, body, PIPELINE))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("got 404")
                    .hasMessageContaining(PIPELINE);
        }
    }

    @Test
    void refusesAnAnswerThatCarriesNoState() {
        assertThatThrownBy(
                        () ->
                                ControlPlane.interpretState(
                                        200, JsonWriter.write(Map.of("pipelineId", PIPELINE)), PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("carried no state");
    }

    // The metrics face is read the same way, so the same distinctions are pinned here: only no-observation
    // reads as "nothing yet", and a published observation is required to carry the errorCount the runtime
    // derives from the state, so an answer missing it is a contract regression, surfaced rather than waited out.

    @Test
    void readsThePublishedErrorCount() {
        assertThat(ControlPlane.interpretErrorCount(200, metrics(1), PIPELINE)).contains(1L);
    }

    @Test
    void readsTheProductsNoObservationCodeAsNothingPublishedYetForMetrics() {
        assertThat(ControlPlane.interpretErrorCount(404, coded(MonitorError.NO_OBSERVATION.code()), PIPELINE))
                .isEmpty();
    }

    @Test
    void keepsAnotherCodesNotFoundLoudForMetrics() {
        assertThatThrownBy(
                        () ->
                                ControlPlane.interpretErrorCount(
                                        404, coded(LifecycleError.UNKNOWN_PIPELINE.code()), PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("got 404");
    }

    @Test
    void keepsAServerFailureLoudForMetrics() {
        assertThatThrownBy(() -> ControlPlane.interpretErrorCount(500, "boom", PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("got 500");
    }

    @Test
    void refusesAMetricsAnswerThatCarriesNoMetrics() {
        assertThatThrownBy(
                        () ->
                                ControlPlane.interpretErrorCount(
                                        200, JsonWriter.write(Map.of("pipelineId", PIPELINE)), PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("carried no metrics");
    }

    @Test
    void refusesAPublishedObservationThatCarriesNoErrorCount() {
        // The runtime derives errorCount from the actual state, so a published observation always carries it;
        // an answer that does not is the metric wiring having regressed, and the harness says so loudly rather
        // than sitting out its whole bound as though the pipeline were slow to converge.
        assertThatThrownBy(
                        () ->
                                ControlPlane.interpretErrorCount(
                                        200,
                                        JsonWriter.write(Map.of("pipelineId", PIPELINE, "metrics", Map.of())),
                                        PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("carried no errorCount");
    }

    private static String status(String state) {
        return JsonWriter.write(Map.of("pipelineId", PIPELINE, "state", state));
    }

    private static String metrics(long errorCount) {
        return JsonWriter.write(Map.of("pipelineId", PIPELINE, "metrics", Map.of("errorCount", errorCount)));
    }

    /** A structured coded error body, as the product's shared advice renders one. */
    private static String coded(String code) {
        return JsonWriter.write(Map.of("code", code, "params", Map.of("pipeline", PIPELINE), "message", "rendered"));
    }
}
