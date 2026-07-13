package io.cyntex.spi.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.event.Op;
import org.junit.jupiter.api.Test;

/**
 * The two capture phases and their one-way mapping from an event's op. A source is read in a bounded
 * snapshot phase (snapshot reads, op {@code r}) and then an unbounded cdc phase (the row and schema
 * mutations); the phase an event belongs to follows its op.
 */
class CapturePhaseTest {

    @Test
    void thereAreExactlyTwoPhases() {
        assertThat(CapturePhase.values()).containsExactly(CapturePhase.SNAPSHOT, CapturePhase.CDC);
    }

    @Test
    void aSnapshotReadBelongsToTheSnapshotPhase() {
        assertThat(CapturePhase.of(Op.READ)).isEqualTo(CapturePhase.SNAPSHOT);
    }

    @Test
    void everyChangeMutationBelongsToTheCdcPhase() {
        assertThat(CapturePhase.of(Op.INSERT)).isEqualTo(CapturePhase.CDC);
        assertThat(CapturePhase.of(Op.UPDATE)).isEqualTo(CapturePhase.CDC);
        assertThat(CapturePhase.of(Op.DELETE)).isEqualTo(CapturePhase.CDC);
        assertThat(CapturePhase.of(Op.DDL)).isEqualTo(CapturePhase.CDC);
    }

    @Test
    void readIsTheOnlyOpInTheSnapshotPhase() {
        for (Op op : Op.values()) {
            assertThat(CapturePhase.of(op) == CapturePhase.SNAPSHOT)
                    .as("only a snapshot read is in the snapshot phase; %s", op)
                    .isEqualTo(op == Op.READ);
        }
    }
}
