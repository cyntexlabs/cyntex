package io.cyntex.runtime.srs;

import io.cyntex.core.event.Envelope;

/**
 * Projects a cdc change out of the per-table change ring into the transform-facing envelope currency.
 * This is the one place the source position enters that currency: the item's opaque position token
 * becomes the envelope's {@code srcPos}, which the transform chain carries through and the sink acks back
 * so the durable source-read frontier never passes an unacked change.
 *
 * <p>The stream name is injected, not read from the item — the ring is per-table, so the item does not
 * carry one; the reader is bound to a source vertex that knows the logical stream it feeds. Schema stays
 * null in the lean tier: the item points at schema history by version rather than repeating it inline.
 * Snapshot reads never reach here (they bypass the ring), so a projected event always has a position.
 */
final class SrsProjection {

    private SrsProjection() {
    }

    /** The envelope for one ring item on the stream {@code src}, carrying the item's source position. */
    static Envelope toEnvelope(SrsItem item, String src) {
        return new Envelope(
                item.op(), item.ts(), src, item.before(), item.after(), null, item.srcPos().token());
    }
}
