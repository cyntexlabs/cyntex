package io.cyntex.spi.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.model.ReadMode;
import org.junit.jupiter.api.Test;

/**
 * The read-mode consumption intent: which capture phases a pipeline runs, derived from its read
 * mode. This is the offline half of the read axis — the same rule the validate layer enforces
 * (snapshot_only has no change tail; cdc_only has no initial snapshot) seen from the capture side.
 */
class CapturePlanTest {

    @Test
    void snapshotAndCdcRunsBothPhases() {
        CapturePlan plan = CapturePlan.forReadMode(ReadMode.SNAPSHOT_AND_CDC);

        assertThat(plan.snapshot()).isTrue();
        assertThat(plan.cdc()).isTrue();
    }

    @Test
    void cdcOnlySkipsTheSnapshotAndStreamsTheChangeTail() {
        CapturePlan plan = CapturePlan.forReadMode(ReadMode.CDC_ONLY);

        assertThat(plan.snapshot()).isFalse();
        assertThat(plan.cdc()).isTrue();
    }

    @Test
    void snapshotOnlyRunsTheBoundedSnapshotWithoutAChangeTail() {
        CapturePlan plan = CapturePlan.forReadMode(ReadMode.SNAPSHOT_ONLY);

        assertThat(plan.snapshot()).isTrue();
        assertThat(plan.cdc()).isFalse();
    }

    @Test
    void everyReadModeRunsAtLeastOnePhase() {
        for (ReadMode mode : ReadMode.values()) {
            CapturePlan plan = CapturePlan.forReadMode(mode);
            assertThat(plan.snapshot() || plan.cdc())
                    .as("read mode %s must run a snapshot, a cdc tail, or both", mode)
                    .isTrue();
        }
    }

    @Test
    void snapshotOnlyIsTheOnlyModeWithoutACdcTail() {
        for (ReadMode mode : ReadMode.values()) {
            boolean hasTail = CapturePlan.forReadMode(mode).cdc();
            assertThat(hasTail)
                    .as("only snapshot_only has no cdc tail; %s", mode)
                    .isEqualTo(mode != ReadMode.SNAPSHOT_ONLY);
        }
    }
}
