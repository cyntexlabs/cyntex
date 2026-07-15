package io.cyntex.e2e;

import io.cyntex.control.core.MonitorError;
import io.cyntex.core.common.JsonWriter;
import io.cyntex.core.lifecycle.LifecycleError;
import io.cyntex.core.lifecycle.PipelineState;
import org.junit.jupiter.api.Test;

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

    @Test
    void keepsAnotherCodesNotFoundLoud() {
        // The case a rule written on the status alone would get wrong: this 404 says the pipeline does not
        // exist, and reading it as "merely slow to converge" would make the specification wait out its whole
        // bound and then report the wrong thing entirely.
        assertThatThrownBy(
                        () ->
                                ControlPlane.interpretState(
                                        404, coded(LifecycleError.UNKNOWN_PIPELINE.code()), PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(LifecycleError.UNKNOWN_PIPELINE.code());
    }

    @Test
    void keepsAServerFailureLoud() {
        assertThatThrownBy(() -> ControlPlane.interpretState(500, "boom", PIPELINE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("got 500");
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

    private static String status(String state) {
        return JsonWriter.write(Map.of("pipelineId", PIPELINE, "state", state));
    }

    /** A structured coded error body, as the product's shared advice renders one. */
    private static String coded(String code) {
        return JsonWriter.write(Map.of("code", code, "params", Map.of("pipeline", PIPELINE), "message", "rendered"));
    }
}
