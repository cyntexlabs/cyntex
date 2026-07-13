package io.cyntex.core.lifecycle;

/**
 * A table's initial-snapshot progress: how far the full load of one table has advanced. This is the
 * per-table shape inside an observation's snapshot dataset. {@code rowsTotal} and {@code donePct} are
 * nullable: a null total means the total is not yet wired (unavailable) — it is never faked as 0 or as
 * 100%. Progress with no total is honest partial data, not a completed load.
 *
 * @param rowsDone  rows loaded so far
 * @param rowsTotal the table's total row estimate, or null when unavailable
 * @param donePct   the completion percentage, or null when the total is unavailable
 */
public record TableSnapshot(long rowsDone, Long rowsTotal, Integer donePct) {
}
