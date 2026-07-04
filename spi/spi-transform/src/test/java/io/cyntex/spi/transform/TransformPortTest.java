package io.cyntex.spi.transform;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.event.Envelope;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The transform port seen through stub implementations: proves the row-level seam is implementable
 * and pins the shape the port documents — a stateless transform of one event into zero-or-more
 * events, so a map keeps the one, a filter drops it, and a fan-out yields several.
 */
class TransformPortTest {

    @Test
    void mapProducesExactlyOneEventPerEvent() {
        TransformPort renameToUpper = event -> List.of(
                Envelope.insert(event.ts(), event.src().toUpperCase(), event.after(), event.schema()));

        List<Envelope> out = renameToUpper.transform(Envelope.insert(1L, "orders", Map.of("id", 1), null));

        assertThat(out).singleElement().satisfies(e -> assertThat(e.src()).isEqualTo("ORDERS"));
    }

    @Test
    void filterDropsAnEventByProducingNoEvents() {
        TransformPort dropDeletes =
                event -> event.op() == io.cyntex.core.event.Op.DELETE ? List.of() : List.of(event);

        assertThat(dropDeletes.transform(Envelope.delete(1L, "orders", Map.of("id", 1), null))).isEmpty();
        assertThat(dropDeletes.transform(Envelope.insert(2L, "orders", Map.of("id", 2), null))).hasSize(1);
    }

    @Test
    void aStatelessTransformCanFanOutToManyEvents() {
        TransformPort duplicate = event -> List.of(event, event);

        assertThat(duplicate.transform(Envelope.insert(1L, "orders", Map.of("id", 1), null))).hasSize(2);
    }
}
