package io.cyntex.runtime.srs;

import io.cyntex.core.model.ReadMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a pipeline's read mode and its source's {@code srs.enabled} flag branch the capture topology: whether
 * to run the bounded snapshot phase, whether to attach an incremental tail, and — when there is a tail —
 * whether it flows through the shared replay ring (registering this pipeline's consumer cursor) or straight
 * to the single consumer with no shared buffering. {@code srs.enabled} only matters when there is a tail.
 */
class ConsumptionPlanTest {

    @Test
    void snapshotAndCdcWithSrsRunsSnapshotThenSharedRingTail() {
        ConsumptionPlan p = ConsumptionPlan.of(ReadMode.SNAPSHOT_AND_CDC, true);

        assertThat(p.snapshot()).isTrue();
        assertThat(p.tail()).isTrue();
        assertThat(p.sharedRing()).isTrue();
        assertThat(p.directTail()).isFalse();
        assertThat(p.registersConsumerCursor()).isTrue();
    }

    @Test
    void snapshotAndCdcWithoutSrsStreamsTheTailStraightToTheConsumer() {
        ConsumptionPlan p = ConsumptionPlan.of(ReadMode.SNAPSHOT_AND_CDC, false);

        assertThat(p.snapshot()).isTrue();
        assertThat(p.tail()).isTrue();
        assertThat(p.sharedRing()).isFalse();
        assertThat(p.directTail()).isTrue();
        assertThat(p.registersConsumerCursor()).isFalse();
    }

    @Test
    void cdcOnlyWithSrsSkipsSnapshotAndAttachesSharedRingTail() {
        ConsumptionPlan p = ConsumptionPlan.of(ReadMode.CDC_ONLY, true);

        assertThat(p.snapshot()).isFalse();
        assertThat(p.tail()).isTrue();
        assertThat(p.sharedRing()).isTrue();
        assertThat(p.registersConsumerCursor()).isTrue();
    }

    @Test
    void cdcOnlyWithoutSrsSkipsSnapshotAndStreamsDirect() {
        ConsumptionPlan p = ConsumptionPlan.of(ReadMode.CDC_ONLY, false);

        assertThat(p.snapshot()).isFalse();
        assertThat(p.tail()).isTrue();
        assertThat(p.directTail()).isTrue();
        assertThat(p.registersConsumerCursor()).isFalse();
    }

    @Test
    void snapshotOnlyRunsSnapshotWithNoTailAndNoConsumerCursor() {
        ConsumptionPlan p = ConsumptionPlan.of(ReadMode.SNAPSHOT_ONLY, true);

        assertThat(p.snapshot()).isTrue();
        assertThat(p.tail()).isFalse();
        assertThat(p.sharedRing()).isFalse();
        assertThat(p.directTail()).isFalse();
        assertThat(p.registersConsumerCursor()).isFalse();
    }

    @Test
    void snapshotOnlyIgnoresSrsEnabledSinceThereIsNoTail() {
        // With no tail, the srs.enabled flag has nothing to decouple, so it does not change the plan.
        assertThat(ConsumptionPlan.of(ReadMode.SNAPSHOT_ONLY, false))
                .isEqualTo(ConsumptionPlan.of(ReadMode.SNAPSHOT_ONLY, true));
    }
}
