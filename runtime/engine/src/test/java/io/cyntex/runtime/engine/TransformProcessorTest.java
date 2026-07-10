package io.cyntex.runtime.engine;

import com.hazelcast.jet.core.test.TestSupport;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.transform.TransformPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The generic adapter that wraps a stateless {@link TransformPort} into a Jet processor: for each
 * inbound event it emits every event the port returns, drops the event when the port returns none,
 * and fans out when the port returns several. The bounded-outbox scenarios TestSupport runs also
 * pin that the adapter, not the port, carries the emit-side backpressure.
 */
class TransformProcessorTest {

    private static Envelope event(int id) {
        return Envelope.insert(id, "orders", Map.of("id", id), null);
    }

    @Test
    void map_keeps_the_one_event() {
        TransformPort identity = e -> List.of(e);
        TestSupport.verifyProcessor(() -> new TransformProcessor(identity))
                .input(List.of(event(1), event(2)))
                .expectOutput(List.of(event(1), event(2)));
    }

    @Test
    void filter_drops_by_emitting_none() {
        TransformPort dropAll = e -> List.of();
        TestSupport.verifyProcessor(() -> new TransformProcessor(dropAll))
                .input(List.of(event(1), event(2)))
                .expectOutput(List.of());
    }

    @Test
    void fan_out_emits_each_returned_event() {
        TransformPort duplicate = e -> List.of(e, e);
        TestSupport.verifyProcessor(() -> new TransformProcessor(duplicate))
                .input(List.of(event(1)))
                .expectOutput(List.of(event(1), event(1)));
    }
}
