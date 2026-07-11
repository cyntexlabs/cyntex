package io.cyntex.runtime.srs;

import io.cyntex.spi.store.ConsumerOffset;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * The durable-frontier bound on a source-read-offset advance. The in-memory change ring is volatile —
 * after a restart it is gone and the only durable landing for a change is the idempotent sink. So the
 * persisted source read offset must never pass the slowest consumer's sink-acked source position: every
 * change ahead of the offset must stay re-minable from the source, or a change that was only ever in the
 * ring and had not yet reached a sink would be lost.
 *
 * <p>This resolves the safe offset to persist — the reader's candidate clamped so it never passes the
 * minimum sink-acked position across all consumers. It is empty (advance nothing) when no consumer holds
 * the data durably yet: when there are no consumers, or when any consumer has acked nothing (its frontier
 * sits below the origin and pins the whole advance).
 *
 * <p>A source position is an opaque token whose order is connector-defined; this never parses the token,
 * it ranks positions only through the injected {@code positionOrder}.
 */
public final class SrsDurableFrontier {

    private SrsDurableFrontier() {
    }

    /**
     * The source read offset that may be durably persisted: {@code candidate} clamped to not pass the
     * slowest consumer's sink-acked position, or empty when no advance is safe (no consumers, or a
     * consumer has acked nothing). {@code candidate} is the position the reader has read up to.
     */
    public static Optional<String> safeAdvance(
            String candidate,
            Collection<ConsumerOffset> consumers,
            Comparator<String> positionOrder) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(consumers, "consumers");
        Objects.requireNonNull(positionOrder, "positionOrder");
        if (consumers.isEmpty()) {
            return Optional.empty();
        }
        String frontier = null;
        for (ConsumerOffset consumer : consumers) {
            String acked = consumer.sinkAckedSrcpos();
            if (acked == null) {
                return Optional.empty();
            }
            if (frontier == null || positionOrder.compare(acked, frontier) < 0) {
                frontier = acked;
            }
        }
        String safe = positionOrder.compare(candidate, frontier) < 0 ? candidate : frontier;
        return Optional.of(safe);
    }
}
