package io.cyntex.app;

import com.hazelcast.function.ComparatorEx;

/**
 * The single L1 mock source-position order, shared by the capture side and the sink-ack side so the two
 * cannot drift. A source position is opaque and never ordered lexically; the mock watermark emits
 * {@code w1, w2, ...} and this ranks them by their numeric suffix (so {@code w2} precedes {@code w10},
 * which a lexical order would reverse). The capture watermark that emits the token and this order that
 * ranks it are a matched pair; both the source-read frontier and the sink's contiguous-acked-prefix
 * advance must speak this one order, or an ack would land on a position the frontier cannot compare.
 *
 * <p>A {@link ComparatorEx} so it is serializable and can travel on the DAG to the member that runs the
 * sink; it is equally a plain {@link java.util.Comparator} for the in-process capture side.
 */
final class MockPositionOrder {

    /** Orders the mock watermark positions {@code w1 < w2 < ...} by numeric suffix, never lexically. */
    static final ComparatorEx<String> INSTANCE = (a, b) -> Integer.compare(suffix(a), suffix(b));

    private MockPositionOrder() {
    }

    private static int suffix(String token) {
        return Integer.parseInt(token.substring(1));
    }
}
