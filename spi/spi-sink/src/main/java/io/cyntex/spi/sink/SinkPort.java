package io.cyntex.spi.sink;

/**
 * The write side of a connector: delivery of change events to a target. A pure interface over the
 * standard event envelope; it depends on the core ring only (rule R2) and names no
 * connector-specific type.
 *
 * <p>Opening a writer binds the port to a resolved {@link SinkConfig} and yields a {@link SinkWriter}
 * that holds the target resource across many batches. Writes are asynchronous — the port defines the
 * shape of delivery, not its pacing: how many writes are in flight, and the backpressure that bounds
 * them, are the runtime's concern, not the port's.
 */
public interface SinkPort {

    /**
     * Opens a writer bound to the given configuration. The returned writer holds a target resource
     * and must be closed.
     */
    SinkWriter open(SinkConfig config);
}
