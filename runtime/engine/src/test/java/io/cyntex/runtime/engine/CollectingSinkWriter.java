package io.cyntex.runtime.engine;

import io.cyntex.core.event.Envelope;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteResult;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A test sink writer that records each event's {@code id} into a named JVM-static queue, so an
 * embedded run can assert what a built DAG delivered to its sink. It is keyed by name rather than
 * held by the test because the writer is opened member-side behind the sink seam, out of the test's
 * direct reach. It completes each write synchronously.
 */
final class CollectingSinkWriter implements SinkWriter {

    private static final Map<String, Queue<Integer>> SINKS = new ConcurrentHashMap<>();

    private final String name;

    CollectingSinkWriter(String name) {
        this.name = name;
    }

    /** The queue of ids written to the named sink, created on first use. */
    static Queue<Integer> collected(String name) {
        return SINKS.computeIfAbsent(name, key -> new ConcurrentLinkedQueue<>());
    }

    /** Clears the named sink before a run so a test sees only its own writes. */
    static void reset(String name) {
        SINKS.remove(name);
    }

    @Override
    public CompletionStage<WriteResult> write(List<Envelope> records) {
        Queue<Integer> queue = collected(name);
        for (Envelope record : records) {
            queue.add((Integer) record.after().get("id"));
        }
        return CompletableFuture.completedFuture(new WriteResult(records.size()));
    }

    @Override
    public void close() {
    }
}
