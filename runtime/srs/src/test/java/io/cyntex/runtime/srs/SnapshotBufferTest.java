package io.cyntex.runtime.srs;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.event.Envelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The member-local snapshot buffer: the seam that carries a source's bounded snapshot rows from the capture
 * side to the source vertex so they can be emitted ahead of the cdc tail. It buffers per ring name, drains a
 * ring's rows once in append order, and isolates one ring's rows from another's.
 */
class SnapshotBufferTest {

    private static Envelope row(String ring, int id) {
        return Envelope.read(id, ring, Map.of("id", (long) id), Map.of());
    }

    @Test
    void drainsOneRingsRowsInAppendOrder() {
        SnapshotBuffer buffer = new SnapshotBuffer();
        buffer.append("srs.chain.orders", row("orders", 0));
        buffer.append("srs.chain.orders", row("orders", 1));
        buffer.append("srs.chain.orders", row("orders", 2));

        List<Envelope> drained = buffer.drain("srs.chain.orders");

        assertThat(drained).extracting(e -> e.after().get("id")).containsExactly(0L, 1L, 2L);
    }

    @Test
    void drainIsOnceConsumedSoASecondDrainIsEmpty() {
        SnapshotBuffer buffer = new SnapshotBuffer();
        buffer.append("srs.chain.orders", row("orders", 0));

        assertThat(buffer.drain("srs.chain.orders")).hasSize(1);
        assertThat(buffer.drain("srs.chain.orders")).isEmpty();
    }

    @Test
    void drainingANeverAppendedRingIsEmptyNotNull() {
        SnapshotBuffer buffer = new SnapshotBuffer();

        assertThat(buffer.drain("srs.chain.absent")).isEmpty();
    }

    @Test
    void keepsEachRingsRowsIsolated() {
        SnapshotBuffer buffer = new SnapshotBuffer();
        buffer.append("srs.chain.orders", row("orders", 1));
        buffer.append("srs.chain.items", row("items", 2));

        assertThat(buffer.drain("srs.chain.orders")).extracting(e -> e.after().get("id")).containsExactly(1L);
        assertThat(buffer.drain("srs.chain.items")).extracting(e -> e.after().get("id")).containsExactly(2L);
    }
}
