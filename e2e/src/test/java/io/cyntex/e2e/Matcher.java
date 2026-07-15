package io.cyntex.e2e;

import io.cyntex.core.lifecycle.PipelineState;

import java.util.Map;

/**
 * A condition over observable product state. One vocabulary serves both timings: {@code assert}
 * checks a matcher once, {@code await} polls the same matcher until it holds or the bound expires.
 *
 * <p>The vocabulary is deliberately two words wide. Both have a real source today: {@code count}
 * reads the target database, {@code state} reads the published observation. A word whose source the
 * runtime does not yet populate would poll an empty map until timeout, so it is not admitted until
 * something fills it.
 */
public sealed interface Matcher {

    /** Row counts per table, read from the target endpoint itself. */
    record Count(Map<TableAlias, Long> expected) implements Matcher {
        public Count {
            expected = Map.copyOf(expected);
        }
    }

    /** Lifecycle state per pipeline, read from the published observation. */
    record State(Map<String, PipelineState> expected) implements Matcher {
        public State {
            expected = Map.copyOf(expected);
        }
    }

    static Matcher count(TableAlias table, long rows) {
        return new Count(Map.of(table, rows));
    }

    static Matcher state(String pipelineId, PipelineState state) {
        return new State(Map.of(pipelineId, state));
    }
}
