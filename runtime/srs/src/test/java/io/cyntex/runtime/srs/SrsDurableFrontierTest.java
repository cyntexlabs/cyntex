package io.cyntex.runtime.srs;

import io.cyntex.spi.store.ConsumerOffset;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The durable-frontier bound on a source-read-offset advance: the persisted offset must never pass the
 * slowest consumer's sink-acked source position, so every change past the offset is still re-minable from
 * the source after a restart (the volatile ring is gone; the idempotent sink is the only durable landing).
 * A source position is an opaque token whose order is connector-defined — the gate never parses it, it
 * ranks positions through an injected order.
 *
 * <p>The tests model a source stream as numeric-token positions ordered by their value; one test uses a
 * gtid-shaped token to show the injected order — not lexicographic string order — is what decides.
 */
class SrsDurableFrontierTest {

    /** A source-position order for numeric tokens ("3" &lt; "5" &lt; "10"); stands in for a real connector's order. */
    private static final Comparator<String> NUMERIC = Comparator.comparingLong(Long::parseLong);

    private static ConsumerOffset acked(String pipelineId, String sinkAckedSrcpos) {
        return new ConsumerOffset(pipelineId, Map.of(), sinkAckedSrcpos);
    }

    @Test
    void advancesToTheReaderCandidateWhenItTrailsEveryConsumer() {
        List<ConsumerOffset> consumers = List.of(acked("p1", "5"), acked("p2", "8"));
        // The reader has read up to position 4, behind the slowest consumer's ack (5) -> its full progress
        // is safe to persist.
        assertThat(SrsDurableFrontier.safeAdvance("4", consumers, NUMERIC)).hasValue("4");
    }

    @Test
    void clampsToTheSlowestConsumerSoUnackedEventsStayReplayable() {
        List<ConsumerOffset> consumers = List.of(acked("p1", "5"), acked("p2", "8"));
        // The reader has read up to 10, but the slowest consumer has only acked 5. Persisting 10 would let
        // a restart re-mine from 10 and lose 6..9 (only ever in the volatile ring). The offset is clamped
        // to 5, so a restart re-mines 6..10 -> replayable, none lost.
        assertThat(SrsDurableFrontier.safeAdvance("10", consumers, NUMERIC)).hasValue("5");
    }

    @Test
    void refusesToAdvanceWhenAnyConsumerHasAckedNothing() {
        List<ConsumerOffset> consumers = List.of(acked("p1", "8"), acked("p2", null));
        // p2 has sunk nothing, so its acked frontier is below the origin: the offset must not advance at
        // all, or a restart would strand every change p2 has yet to land.
        assertThat(SrsDurableFrontier.safeAdvance("10", consumers, NUMERIC)).isEmpty();
    }

    @Test
    void refusesToAdvanceWhenThereAreNoConsumers() {
        // No consumer holds the data durably yet -> nothing is safe to advance past.
        assertThat(SrsDurableFrontier.safeAdvance("10", List.of(), NUMERIC)).isEmpty();
    }

    @Test
    void boundsByTheSingleConsumersAck() {
        List<ConsumerOffset> consumers = List.of(acked("p1", "7"));
        assertThat(SrsDurableFrontier.safeAdvance("10", consumers, NUMERIC)).hasValue("7");
    }

    @Test
    void honorsTheInjectedOrderNotLexicographicStringOrder() {
        // gtid-shaped tokens ordered by their trailing sequence: :9 (9) < :100 (100). Lexicographically
        // ":100" < ":9", which would (wrongly) treat the candidate as trailing the ack. The gate must use
        // the injected order: frontier :9, candidate :100 is ahead -> clamp to :9.
        Comparator<String> gtidOrder =
                Comparator.comparingLong(t -> Long.parseLong(t.substring(t.lastIndexOf(':') + 1)));
        List<ConsumerOffset> consumers = List.of(acked("p1", "gtid:aaa:9"));
        assertThat(SrsDurableFrontier.safeAdvance("gtid:aaa:100", consumers, gtidOrder))
                .hasValue("gtid:aaa:9");
    }

    @Test
    void rejectsANullCandidate() {
        assertThatThrownBy(() -> SrsDurableFrontier.safeAdvance(null, List.of(), NUMERIC))
                .isInstanceOf(NullPointerException.class);
    }
}
