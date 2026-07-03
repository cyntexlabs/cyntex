package io.cyntex.core.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The checkpoint doc shape and its invariants — the per-pipeline persistence unit that the CAS
 * swaps. The real Mongo serialization lives in an adapter; here the record <em>is</em> the contract.
 */
class CheckpointDocTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

    @Test
    @DisplayName("a fresh checkpoint starts at epoch 0")
    void initialStartsAtEpochZero() {
        CheckpointDoc doc = CheckpointDoc.initial("p1", "NEW", T0);

        assertThat(doc.pipelineId()).isEqualTo("p1");
        assertThat(doc.stateJson()).isEqualTo("NEW");
        assertThat(doc.epoch()).isZero();
        assertThat(doc.touchTime()).isEqualTo(T0);
    }

    @Test
    @DisplayName("the reference fields are required (a null is a programmer error, not a coded one)")
    void rejectsNullFields() {
        assertThatThrownBy(() -> new CheckpointDoc(null, "NEW", 0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CheckpointDoc("p1", null, 0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CheckpointDoc("p1", "NEW", 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("epoch can never be negative")
    void rejectsNegativeEpoch() {
        assertThatThrownBy(() -> new CheckpointDoc("p1", "NEW", -1, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
