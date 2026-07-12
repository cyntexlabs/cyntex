package io.cyntex.runtime.srs;

import io.cyntex.core.model.ReadMode;
import io.cyntex.spi.capture.CapturePlan;

/**
 * How a pipeline consumes a source, branched from its read mode and its source's {@code srs.enabled} flag.
 * It extends the read-mode-only {@link CapturePlan} (which decides the snapshot / cdc phases) with the SRS
 * dimension: when there is an incremental tail, whether it flows through the shared replay ring or straight
 * to the single consumer.
 *
 * <ul>
 *   <li>{@code snapshot} — run the bounded snapshot phase (pass its rows straight to the sink);</li>
 *   <li>{@code tail} — attach the unbounded incremental tail;</li>
 *   <li>{@code sharedRing} — the tail flows through the shared per-table replay ring, and this pipeline
 *       registers its own consumer cursor on the mining chain; when false with a tail, the tail streams
 *       straight to this one consumer with no shared buffering (the {@code srs.enabled: false} path).</li>
 * </ul>
 *
 * <p>Only a shared-ring tail registers a consumer cursor: a snapshot-only read has no tail, and a direct
 * tail has no shared ring to position a cursor into. {@code srs.enabled} is immaterial without a tail.
 */
public record ConsumptionPlan(boolean snapshot, boolean tail, boolean sharedRing) {

    public ConsumptionPlan {
        if (!tail && sharedRing) {
            throw new IllegalArgumentException("a shared ring needs a tail to carry");
        }
    }

    /** The consumption plan for a read mode and whether the source's replay store is provisioned. */
    public static ConsumptionPlan of(ReadMode mode, boolean srsEnabled) {
        CapturePlan phases = CapturePlan.forReadMode(mode);
        boolean tail = phases.cdc();
        return new ConsumptionPlan(phases.snapshot(), tail, tail && srsEnabled);
    }

    /** The tail streams straight to the single consumer with no shared buffering (srs disabled). */
    public boolean directTail() {
        return tail && !sharedRing;
    }

    /** Whether this pipeline registers its own consumer cursor — only a shared-ring tail does. */
    public boolean registersConsumerCursor() {
        return sharedRing;
    }
}
