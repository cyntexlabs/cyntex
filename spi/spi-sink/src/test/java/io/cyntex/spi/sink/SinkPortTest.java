package io.cyntex.spi.sink;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.event.Envelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * The sink port seen through a stub implementation: proves the write-side contract is implementable
 * and usable, and pins the shape the port documents — an asynchronous batch write returning a
 * completion stage, a writer that spans many batches, and an idempotent close.
 */
class SinkPortTest {

    private static final SinkConfig CONFIG =
            new SinkConfig("mysql", Map.of(), WriteMode.UPSERT, DdlPolicy.FAIL);

    @Test
    void writeIsAsynchronousAndCompletesWithTheCountWritten() {
        try (SinkWriter writer = new StubSink().open(CONFIG)) {
            CompletionStage<WriteResult> stage = writer.write(List.of(
                    Envelope.insert(1L, "orders", Map.of("id", 1), null),
                    Envelope.insert(2L, "orders", Map.of("id", 2), null)));

            assertThat(stage.toCompletableFuture().join().written()).isEqualTo(2L);
        }
    }

    @Test
    void writingAnEmptyBatchCompletesWithZero() {
        try (SinkWriter writer = new StubSink().open(CONFIG)) {
            assertThat(writer.write(List.of()).toCompletableFuture().join().written()).isZero();
        }
    }

    @Test
    void oneWriterSpansManyBatchesAndReleasesOnceOnClose() {
        StubSink sink = new StubSink();
        SinkWriter writer = sink.open(CONFIG);

        writer.write(List.of(Envelope.insert(1L, "orders", Map.of("id", 1), null)));
        writer.write(List.of(
                Envelope.update(2L, "orders", Map.of("id", 1), Map.of("id", 1, "n", 9), null),
                Envelope.delete(3L, "orders", Map.of("id", 2), null)));

        writer.close();
        writer.close();

        assertThat(sink.lastWriter.records).hasSize(3);
        // the underlying target is released once, not once per close() call
        assertThat(sink.lastWriter.releaseRuns).isEqualTo(1);
    }

    @Test
    void writeModeIsAClosedSetOfTwo() {
        assertThat(WriteMode.values()).containsExactly(WriteMode.APPEND, WriteMode.UPSERT);
    }

    @Test
    void ddlPolicyIsAClosedSetOfThree() {
        assertThat(DdlPolicy.values()).containsExactly(DdlPolicy.APPLY, DdlPolicy.IGNORE, DdlPolicy.FAIL);
    }

    /** A minimal in-memory sink: records everything written, models an idempotent close. */
    private static final class StubSink implements SinkPort {

        RecordingWriter lastWriter;

        @Override
        public SinkWriter open(SinkConfig config) {
            lastWriter = new RecordingWriter();
            return lastWriter;
        }
    }

    /** Models an idempotent close: the target resource is released at most once. */
    private static final class RecordingWriter implements SinkWriter {

        final List<Envelope> records = new ArrayList<>();
        int releaseRuns;
        private boolean closed;

        @Override
        public CompletionStage<WriteResult> write(List<Envelope> batch) {
            records.addAll(batch);
            return CompletableFuture.completedFuture(new WriteResult(batch.size()));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            releaseRuns++;
        }
    }
}
