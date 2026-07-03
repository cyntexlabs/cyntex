package io.cyntex.core.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compare-and-swap on the fencing epoch — the only legal way a transition lands in the checkpoint.
 * A swap succeeds iff the writer's expected epoch equals the stored one, which atomically bumps the
 * epoch and swaps the state; a stale writer is fenced. Pure function, no store.
 */
class EpochCasTest {

    private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-01T00:05:00Z");

    @Test
    @DisplayName("a matching epoch applies: epoch bumps by one, state swaps, touch refreshes")
    void matchingEpochAppliesAndIncrements() {
        CheckpointDoc current = new CheckpointDoc("p1", "RUNNING", 3, T0);

        CasOutcome outcome = EpochCas.swap(current, 3, "PAUSED", T1);

        assertThat(outcome).isInstanceOfSatisfying(CasOutcome.Applied.class, applied -> {
            CheckpointDoc next = applied.next();
            assertThat(next.pipelineId()).isEqualTo("p1");
            assertThat(next.epoch()).isEqualTo(4);
            assertThat(next.stateJson()).isEqualTo("PAUSED");
            assertThat(next.touchTime()).isEqualTo(T1);
        });
    }

    @Test
    @DisplayName("a stale epoch is fenced and reports the stored epoch, without producing a next doc")
    void staleEpochIsFenced() {
        CheckpointDoc current = new CheckpointDoc("p1", "RUNNING", 5, T0);

        CasOutcome outcome = EpochCas.swap(current, 4, "STOPPED", T1);

        assertThat(outcome).isInstanceOfSatisfying(CasOutcome.Fenced.class,
                fenced -> assertThat(fenced.currentEpoch()).isEqualTo(5));
    }

    @Test
    @DisplayName("an epoch far behind the stored one is fenced just the same")
    void farBehindEpochIsFenced() {
        CheckpointDoc current = new CheckpointDoc("p1", "RUNNING", 9, T0);

        CasOutcome outcome = EpochCas.swap(current, 0, "STOPPED", T1);

        assertThat(outcome).isInstanceOf(CasOutcome.Fenced.class);
    }

    @Test
    @DisplayName("successive applied swaps make the epoch strictly monotonic")
    void successiveSwapsAreStrictlyMonotonic() {
        CheckpointDoc d0 = CheckpointDoc.initial("p1", "NEW", T0);
        CheckpointDoc d1 = ((CasOutcome.Applied) EpochCas.swap(d0, 0, "RUNNING", T0)).next();
        CheckpointDoc d2 = ((CasOutcome.Applied) EpochCas.swap(d1, 1, "PAUSED", T1)).next();

        assertThat(d0.epoch()).isEqualTo(0);
        assertThat(d1.epoch()).isEqualTo(1);
        assertThat(d2.epoch()).isEqualTo(2);
    }
}
