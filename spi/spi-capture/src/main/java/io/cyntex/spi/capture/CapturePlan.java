package io.cyntex.spi.capture;

import io.cyntex.core.model.ReadMode;

/**
 * Which capture phases a pipeline runs, derived from its read mode — the read-side reading of the
 * snapshot × change-tail axis. A plan says whether to run the bounded snapshot phase and whether to
 * attach the unbounded cdc phase:
 *
 * <ul>
 *   <li>{@code snapshot_and_cdc} → snapshot then cdc (the default): a full initial snapshot, then the
 *       continuous change tail;</li>
 *   <li>{@code cdc_only} → cdc only: skip the initial snapshot, read only the change tail;</li>
 *   <li>{@code snapshot_only} → snapshot only: read the current rows once as a bounded pass, no tail.</li>
 * </ul>
 *
 * <p>This mirrors the read-axis the validate layer enforces from the authoring side: {@code cdc_only}
 * has no initial snapshot, {@code snapshot_only} has no change tail. Every read mode runs at least one
 * phase.
 */
public record CapturePlan(boolean snapshot, boolean cdc) {

    /** The capture plan for a pipeline read mode. */
    public static CapturePlan forReadMode(ReadMode mode) {
        return switch (mode) {
            case SNAPSHOT_AND_CDC -> new CapturePlan(true, true);
            case CDC_ONLY -> new CapturePlan(false, true);
            case SNAPSHOT_ONLY -> new CapturePlan(true, false);
        };
    }
}
