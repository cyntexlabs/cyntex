package io.cyntex.runtime.srs;

import io.cyntex.core.event.Envelope;
import io.cyntex.spi.capture.CaptureConfig;
import io.cyntex.spi.capture.CapturePort;
import io.cyntex.spi.capture.SourcePosition;
import io.cyntex.spi.capture.Subscription;
import io.cyntex.spi.store.ConsumerOffset;

import java.util.Collection;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The unbounded cdc phase of a capture. Starts the change stream and, for every change event (ops
 * {@code i} / {@code u} / {@code d} / {@code ddl}), projects it to a change-ring item and admits it
 * through the headroom gate into the per-table ring — the only hot buffer the SRS keeps. A snapshot read
 * (op {@code r}) never reaches here; the ring item rejects it by construction.
 *
 * <p>The per-event source position is threaded at this seam: the event envelope carries no position slot,
 * so each change is stamped with the next {@link CdcChain#watermark() watermark} position — at L1 a mock
 * monotonic generator standing in for the connector-defined position order.
 */
public final class CdcPhase {

    private CdcPhase() {
    }

    /**
     * Starts the cdc stream and returns the subscription that stops it. Each change event is projected to
     * a ring item stamped with the next watermark position and appended through the headroom gate, which
     * refuses a write that would overwrite a change the slowest consumer has not read.
     *
     * @param minConsumerReadSeq the slowest consumer's read cursor into the ring, the headroom bound
     * @param consumers          the chain's consumer cursors, the durable-frontier bound on the read offset
     */
    public static Subscription run(
            CapturePort port,
            CaptureConfig config,
            CdcChain chain,
            LongSupplier minConsumerReadSeq,
            Supplier<Collection<ConsumerOffset>> consumers) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(minConsumerReadSeq, "minConsumerReadSeq");
        Objects.requireNonNull(consumers, "consumers");
        return port.cdc(config, event -> writeChange(chain, event, minConsumerReadSeq, consumers));
    }

    /**
     * Projects one change event to a ring item stamped with the next watermark position, admits it through
     * the headroom gate, and advances the durable read offset to its position.
     */
    private static void writeChange(
            CdcChain chain,
            Envelope event,
            LongSupplier minConsumerReadSeq,
            Supplier<Collection<ConsumerOffset>> consumers) {
        SourcePosition pos = chain.watermark().get();
        SrsItem item = new SrsItem(
                pos, event.op(), event.ts(), event.before(), event.after(), chain.schemaVer());
        // Admit the change through the headroom gate. A refused write is backpressure, not a drop: keep
        // retrying against the live consumer cursor so this call -- and with it the source read -- pauses
        // until a consumer frees a slot, rather than overwriting a change no consumer has read.
        while (chain.gate().append(item, minConsumerReadSeq.getAsLong()).isEmpty()) {
            Thread.onSpinWait();
        }
        // The change is in the ring; advance the durable read offset to its position, clamped so it never
        // passes the slowest consumer's sink-acked position -- a change only ever in the volatile ring must
        // stay re-minable from the source until a sink has durably landed it.
        SrsDurableFrontier.safeAdvance(pos.token(), consumers.get(), chain.positionOrder())
                .ifPresent(safe -> chain.meta().advanceSourceReadOffset(chain.miningChainId(), safe));
    }
}
