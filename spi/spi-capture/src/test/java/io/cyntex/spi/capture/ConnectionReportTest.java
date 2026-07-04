package io.cyntex.spi.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.event.Envelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConnectionReportTest {

    private static final DiscoveredSchema SCHEMA =
            new DiscoveredSchema(List.of(new TableSchema("orders", List.of(new FieldSchema("id", "long")))));

    @Test
    void schemaIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionReport(null, List.of()));
    }

    @Test
    void sampleDefaultsToEmptyWhenNull() {
        assertThat(new ConnectionReport(SCHEMA, null).sample()).isEmpty();
    }

    @Test
    void sampleIsADefensiveCopyAndUnmodifiable() {
        List<Envelope> source = new ArrayList<>();
        source.add(Envelope.read(1L, "orders", Map.of("id", 1), null));
        ConnectionReport report = new ConnectionReport(SCHEMA, source);

        source.add(Envelope.read(2L, "orders", Map.of("id", 2), null));

        assertThat(report.sample()).hasSize(1);
        assertThatThrownBy(() -> report.sample().add(Envelope.read(3L, "orders", Map.of("id", 3), null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
