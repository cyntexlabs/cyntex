package io.cyntex.e2e;

import io.cyntex.core.lifecycle.PipelineState;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    /**
     * Row counts per table, read from the target endpoint itself. Declaration order is preserved,
     * so the endpoints are read in the order written and a failure reads the same way twice.
     */
    record Count(Map<TableAlias, Long> expected) implements Matcher {
        public Count {
            expected = Collections.unmodifiableMap(new LinkedHashMap<>(expected));
        }
    }

    /**
     * The lifecycle state of the pipeline this specification names. A specification references
     * exactly one pipeline, and the executor already resolves its id through the product's parser,
     * so naming it again here would only be an id to copy by hand and get wrong.
     */
    record State(PipelineState expected) implements Matcher {}

    static Matcher count(TableAlias table, long rows) {
        return new Count(Map.of(table, rows));
    }

    static Matcher state(PipelineState expected) {
        return new State(expected);
    }
}
