package io.cyntex.control.restapi;

import io.cyntex.core.logging.LogLine;

import java.util.List;

/**
 * The pure log-tail delta a follower stream uses to send only what is new. The node-local sink is a
 * bounded ring: between polls it usually grew (an append) but may also have evicted old lines from the
 * front. Both windows are oldest-to-newest slices of the same append-only stream, so the new lines are
 * the current tail minus its overlap with what was already sent — the longest suffix of {@code previous}
 * that is a prefix of {@code current}. Everything after that overlap is new.
 *
 * <p>Anchoring on the overlap rather than on the last-sent line's last occurrence is what makes a repeated
 * message safe: if the last line sent recurs later in the window, its later copy is a genuinely new line,
 * not the boundary. When there is no overlap at all — a burst evicted the whole sent window — the entire
 * current tail is re-sent (re-tail), the honest behaviour for a bounded sink: a follower may see a line
 * twice, never lose one.
 */
final class LogDelta {

    private LogDelta() {
    }

    /** The lines in {@code current} not yet covered by {@code previous}, oldest to newest. */
    static List<LogLine> newLines(List<LogLine> previous, List<LogLine> current) {
        int overlap = overlap(previous, current);
        return List.copyOf(current.subList(overlap, current.size()));
    }

    /** The length of the longest suffix of {@code previous} that is a prefix of {@code current}. */
    private static int overlap(List<LogLine> previous, List<LogLine> current) {
        int max = Math.min(previous.size(), current.size());
        for (int k = max; k > 0; k--) {
            if (previous.subList(previous.size() - k, previous.size()).equals(current.subList(0, k))) {
                return k;
            }
        }
        return 0;
    }
}
