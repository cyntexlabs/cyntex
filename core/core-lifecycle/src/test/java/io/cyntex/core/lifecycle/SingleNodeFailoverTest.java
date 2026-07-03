package io.cyntex.core.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the fencing contract end-to-end on a single node against an in-memory store double
 * (the future Mongo adapter's stand-in): two owners that both believe they hold the pipeline race a
 * CAS, exactly one wins, the loser is fenced, and the epoch advances strictly monotonically. The
 * real store transaction is not exercised here — only that the pure contract fences correctly.
 */
class SingleNodeFailoverTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-01T00:05:00Z");

    @Test
    @DisplayName("two owners at the same epoch: one CAS wins, the other is fenced, epoch advances once")
    void artificialFailoverFencesTheStaleOwner() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        store.insert(CheckpointDoc.initial("p1", "RUNNING", T0));

        // Both owners read the same doc, so both hold the same expected epoch.
        long ownerAExpected = store.read("p1").epoch();
        long ownerBExpected = store.read("p1").epoch();
        assertThat(ownerAExpected).isEqualTo(ownerBExpected).isEqualTo(0);

        CasOutcome ownerA = store.compareAndSwap("p1", ownerAExpected, "PAUSED", T1);
        CasOutcome ownerB = store.compareAndSwap("p1", ownerBExpected, "STOPPED", T1);

        assertThat(ownerA).isInstanceOf(CasOutcome.Applied.class);
        assertThat(ownerB).isInstanceOfSatisfying(CasOutcome.Fenced.class,
                fenced -> assertThat(fenced.currentEpoch()).isEqualTo(1));

        // The store advanced exactly once and holds only the winner's write.
        CheckpointDoc persisted = store.read("p1");
        assertThat(persisted.epoch()).isEqualTo(1);
        assertThat(persisted.stateJson()).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("a fenced owner must re-read the new epoch before its next CAS can win")
    void fencedOwnerRebasesOnTheNewEpoch() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        store.insert(CheckpointDoc.initial("p1", "RUNNING", T0));
        store.compareAndSwap("p1", 0, "PAUSED", T1);

        // The stale owner still holding epoch 0 is fenced.
        assertThat(store.compareAndSwap("p1", 0, "STOPPED", T1)).isInstanceOf(CasOutcome.Fenced.class);

        // Re-reading the current epoch lets the next CAS win; the epoch advances to 2.
        long fresh = store.read("p1").epoch();
        assertThat(store.compareAndSwap("p1", fresh, "STOPPED", T1)).isInstanceOf(CasOutcome.Applied.class);
        assertThat(store.read("p1").epoch()).isEqualTo(2);
    }
}
