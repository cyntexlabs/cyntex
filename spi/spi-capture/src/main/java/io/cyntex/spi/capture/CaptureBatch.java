package io.cyntex.spi.capture;

import io.cyntex.core.event.Envelope;
import java.util.Iterator;

/**
 * A bounded snapshot read: an iterator of events that also holds a source resource, so it must be
 * closed. Every event it yields is a snapshot read (op {@code r}). Closing releases the underlying
 * source; it is idempotent and may be called before the iterator is drained.
 */
public interface CaptureBatch extends Iterator<Envelope>, AutoCloseable {

    /** Releases the underlying source. Idempotent; may be called before the batch is drained. */
    @Override
    void close();
}
