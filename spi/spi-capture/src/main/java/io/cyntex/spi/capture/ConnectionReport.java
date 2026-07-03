package io.cyntex.spi.capture;

import io.cyntex.core.event.Envelope;
import java.util.List;
import java.util.Objects;

/**
 * The payload of a successful connection test: the schema the source exposes and a small sample of
 * events read from it. {@code sample} is held as an unmodifiable copy; a null list is normalized to
 * empty.
 */
public record ConnectionReport(DiscoveredSchema schema, List<Envelope> sample) {

    public ConnectionReport {
        Objects.requireNonNull(schema, "schema");
        sample = sample == null ? List.of() : List.copyOf(sample);
    }
}
