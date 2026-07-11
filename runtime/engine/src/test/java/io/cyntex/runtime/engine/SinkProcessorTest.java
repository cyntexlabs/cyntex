package io.cyntex.runtime.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import com.hazelcast.jet.core.test.TestSupport;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.sink.SinkWriter;
import io.cyntex.spi.sink.WriteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The generic async sink adapter that drives a {@link SinkWriter} from a Jet vertex: it batches
 * inbound events, keeps a bounded number of writes in flight (the backpressure the port contract
 * hands to the runtime), completes only once every write has settled, and closes the writer once.
 * The write intent (mode / ddl) lives in the writer, not here — this adapter is connector-agnostic.
 */
class SinkProcessorTest {

    private static Envelope event(int id) {
        return Envelope.insert(id, "orders", Map.of("id", id), null);
    }

    /** A sink is terminal: it consumes every event and emits nothing, across all Jet run scenarios. */
    @Test
    void consumes_all_input_and_emits_nothing() {
        TestSupport.verifyProcessor(() -> new SinkProcessor(new RecordingWriter(), 4, 1024))
                .input(List.of(event(1), event(2), event(3)))
                .expectOutput(List.of());
    }

    @Test
    void writes_every_inbound_event_to_the_writer() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        SinkProcessor processor = init(new SinkProcessor(writer, 4, 1024));

        TestInbox inbox = new TestInbox();
        inbox.addAll(List.of(event(1), event(2), event(3)));
        processor.process(0, inbox);
        assertThat(inbox.isEmpty()).isTrue();
        drain(processor);

        assertThat(writer.batches.stream().flatMap(List::stream))
                .containsExactly(event(1), event(2), event(3));
    }

    @Test
    void never_writes_a_batch_larger_than_the_max_batch_size() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        SinkProcessor processor = init(new SinkProcessor(writer, 8, 2));

        TestInbox inbox = new TestInbox();
        inbox.addAll(List.of(event(1), event(2), event(3), event(4), event(5)));
        processor.process(0, inbox);
        drain(processor);

        assertThat(writer.batches).allSatisfy(batch -> assertThat(batch.size()).isLessThanOrEqualTo(2));
        assertThat(writer.batches.stream().flatMap(List::stream)).hasSize(5);
    }

    @Test
    void bounds_the_number_of_in_flight_writes() throws Exception {
        ManualWriter writer = new ManualWriter();
        // one event per write, at most two writes outstanding at a time.
        SinkProcessor processor = init(new SinkProcessor(writer, 2, 1));

        TestInbox inbox = new TestInbox();
        inbox.addAll(List.of(event(1), event(2), event(3), event(4), event(5)));

        processor.process(0, inbox);
        // saturated at two in flight; the remaining three events stay in the inbox for backpressure.
        assertThat(writer.issued()).isEqualTo(2);
        assertThat(inbox).hasSize(3);
        // two writes are still pending, so the processor must not report itself done: a premature
        // complete() would let Jet close the writer and abandon an unsettled write.
        assertThat(processor.complete()).isFalse();

        writer.completeOldest();
        processor.process(0, inbox);
        assertThat(writer.issued()).isEqualTo(3);
        assertThat(inbox).hasSize(2);
        assertThat(processor.complete()).isFalse();

        writer.completeAll();
        processor.process(0, inbox);
        assertThat(inbox.isEmpty()).isTrue();
        writer.completeAll();
        drain(processor);
        assertThat(writer.issued()).isEqualTo(5);
    }

    @Test
    void at_the_single_in_flight_bound_a_batch_settles_before_the_next_is_issued() throws Exception {
        ManualWriter writer = new ManualWriter();
        // one write in flight is the production default: it is what keeps a key's events in order.
        SinkProcessor processor = init(new SinkProcessor(writer, 1, 1));

        TestInbox inbox = new TestInbox();
        inbox.addAll(List.of(event(1), event(2), event(3)));

        processor.process(0, inbox);
        // exactly one write may be outstanding; the rest wait in the inbox, so a later same-key
        // event can never reach the target before an earlier one it depends on.
        assertThat(writer.issued()).isEqualTo(1);
        assertThat(inbox).hasSize(2);

        writer.completeAll();
        processor.process(0, inbox);
        assertThat(writer.issued()).isEqualTo(2);
        assertThat(inbox).hasSize(1);

        writer.completeAll();
        processor.process(0, inbox);
        assertThat(writer.issued()).isEqualTo(3);
        assertThat(inbox.isEmpty()).isTrue();
        writer.completeAll();
        drain(processor);
    }

    @Test
    void closes_the_writer_exactly_once_even_when_close_is_called_twice() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        SinkProcessor processor = init(new SinkProcessor(writer, 4, 1024));
        processor.close();
        processor.close();
        assertThat(writer.closes.get()).isEqualTo(1);
    }

    @Test
    void a_failed_write_propagates_the_cause_not_the_completion_wrapper() throws Exception {
        SinkFailure boom = new SinkFailure("target refused the batch");
        SinkProcessor processor = init(new SinkProcessor(new FailingWriter(boom), 4, 1024));

        TestInbox inbox = new TestInbox();
        inbox.add(event(1));

        assertThatThrownBy(() -> {
            processor.process(0, inbox);
            drain(processor);
        }).isSameAs(boom);
    }

    /** Initialises a processor with a throwaway outbox and context; the sink has no outbound edge. */
    private static SinkProcessor init(SinkProcessor processor) throws Exception {
        processor.init(new TestOutbox(new int[] {}, 128), new TestProcessorContext());
        return processor;
    }

    /** Cooperatively drives complete() to termination (its writes settle in the background). */
    private static void drain(SinkProcessor processor) {
        for (int i = 0; i < 10_000; i++) {
            if (processor.complete()) {
                return;
            }
        }
        throw new AssertionError("processor did not complete");
    }

    /** Records every batch it is handed and completes synchronously. */
    private static final class RecordingWriter implements SinkWriter {
        private final List<List<Envelope>> batches = new CopyOnWriteArrayList<>();
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public CompletionStage<WriteResult> write(List<Envelope> records) {
            batches.add(List.copyOf(records));
            return CompletableFuture.completedFuture(new WriteResult(records.size()));
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    /** Hands out futures the test completes by hand, to observe the in-flight bound. */
    private static final class ManualWriter implements SinkWriter {
        private final List<CompletableFuture<WriteResult>> pending = new ArrayList<>();

        @Override
        public CompletionStage<WriteResult> write(List<Envelope> records) {
            CompletableFuture<WriteResult> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        }

        int issued() {
            return pending.size();
        }

        void completeOldest() {
            for (CompletableFuture<WriteResult> f : pending) {
                if (!f.isDone()) {
                    f.complete(new WriteResult(1));
                    return;
                }
            }
        }

        void completeAll() {
            for (CompletableFuture<WriteResult> f : pending) {
                if (!f.isDone()) {
                    f.complete(new WriteResult(1));
                }
            }
        }

        @Override
        public void close() {
        }
    }

    /** Fails every write with a given cause, to check the cause propagates unwrapped. */
    private static final class FailingWriter implements SinkWriter {
        private final RuntimeException cause;

        FailingWriter(RuntimeException cause) {
            this.cause = cause;
        }

        @Override
        public CompletionStage<WriteResult> write(List<Envelope> records) {
            return CompletableFuture.failedFuture(cause);
        }

        @Override
        public void close() {
        }
    }

    private static final class SinkFailure extends RuntimeException {
        SinkFailure(String message) {
            super(message);
        }
    }
}
