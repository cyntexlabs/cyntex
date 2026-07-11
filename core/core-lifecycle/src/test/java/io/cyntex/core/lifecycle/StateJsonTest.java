package io.cyntex.core.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.cyntex.core.lifecycle.PipelineState.COMPLETED;
import static io.cyntex.core.lifecycle.PipelineState.NEW;
import static io.cyntex.core.lifecycle.PipelineState.PAUSED;
import static io.cyntex.core.lifecycle.PipelineState.RUNNING;
import static io.cyntex.core.lifecycle.PipelineState.STOPPED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The actual-state serializer: how a {@link PipelineState} is encoded into the opaque
 * {@code stateJson} the fencing checkpoint carries. The wire form is the persisted contract the
 * converge side writes and the monitoring read face later reads, so the exact strings are locked.
 */
class StateJsonTest {

    @Test
    @DisplayName("each state serializes to its own stable wire string")
    void serializesEachStateToAStableString() {
        assertThat(StateJson.of(NEW)).isEqualTo("NEW");
        assertThat(StateJson.of(RUNNING)).isEqualTo("RUNNING");
        assertThat(StateJson.of(PAUSED)).isEqualTo("PAUSED");
        assertThat(StateJson.of(STOPPED)).isEqualTo("STOPPED");
        assertThat(StateJson.of(COMPLETED)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("every state maps to a non-blank, distinct wire string")
    void everyStateHasADistinctWireString() {
        List<String> wire = Arrays.stream(PipelineState.values()).map(StateJson::of).toList();

        assertThat(wire).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(wire).allSatisfy(s -> assertThat(s).isNotBlank());
    }
}
