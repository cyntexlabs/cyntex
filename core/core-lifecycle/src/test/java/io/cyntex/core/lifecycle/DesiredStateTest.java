package io.cyntex.core.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The desired-state shape and its invariants — the per-pipeline desired-intent unit the control side
 * writes and the converge side reads. The real Mongo serialization lives in an adapter; here the
 * record <em>is</em> the contract.
 */
class DesiredStateTest {

    @Test
    @DisplayName("a desired state carries its pipeline, target state and revision")
    void carriesItsFields() {
        DesiredState desired = new DesiredState("p1", PipelineState.RUNNING, "rev-abc");

        assertThat(desired.pipelineId()).isEqualTo("p1");
        assertThat(desired.targetState()).isEqualTo(PipelineState.RUNNING);
        assertThat(desired.revision()).isEqualTo("rev-abc");
    }

    @Test
    @DisplayName("every field is required (a null is a programmer error, not a coded one)")
    void rejectsNullFields() {
        assertThatThrownBy(() -> new DesiredState(null, PipelineState.RUNNING, "rev-abc"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DesiredState("p1", null, "rev-abc"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DesiredState("p1", PipelineState.RUNNING, null))
                .isInstanceOf(NullPointerException.class);
    }
}
