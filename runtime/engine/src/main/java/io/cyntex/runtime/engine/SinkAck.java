package io.cyntex.runtime.engine;

import java.io.Serializable;

/**
 * The seam a sink uses to advance one chain's durable sink-acked source position. The processor knows a
 * chain only by the {@code src} stream name its events carry — one single-table chain per stream in L1 —
 * so it advances {@code (chain, position)} and leaves the rest to the binding: the assembly root wires an
 * implementation that maps the stream to its mining chain and pipeline and writes the durable store.
 *
 * <p>{@link Serializable} so it can travel on the DAG to the member that runs the sink, carrying only the
 * coordinates it needs and resolving the store member-side, the same way the source's read-cursor
 * publisher does — the durable store itself never crosses the wire.
 */
@FunctionalInterface
public interface SinkAck extends Serializable {

    /**
     * Advances {@code chain}'s durable watermark to {@code srcpos}. The caller advances a monotonically
     * non-decreasing position — the chain's contiguous acked prefix — so the store persists it as-is.
     */
    void advance(String chain, String srcpos);
}
