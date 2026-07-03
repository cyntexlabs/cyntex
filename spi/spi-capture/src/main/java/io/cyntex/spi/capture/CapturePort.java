package io.cyntex.spi.capture;

/**
 * The read side of a connector: bounded snapshot reads, an unbounded CDC stream, a connection test
 * and schema discovery. A pure interface over the standard event envelope; it depends on the core
 * ring only (rule R2) and names no connector-specific type.
 *
 * <p>Read-side boundary: {@link #snapshot} yields snapshot reads (op {@code r}); {@link #cdc} yields
 * row and schema mutations (ops {@code i} / {@code u} / {@code d} / {@code ddl}). Where a stream
 * resumes from is not part of these signatures — resume position is the caller's concern, not the
 * port's.
 */
public interface CapturePort {

    /**
     * Reads the configured streams once, as a bounded batch of snapshot-read events. The returned
     * batch holds a source resource and must be closed.
     */
    CaptureBatch snapshot(CaptureConfig config);

    /**
     * Starts an unbounded CDC stream, delivering each change event to {@code listener}. The returned
     * subscription stops the stream when closed.
     */
    Subscription cdc(CaptureConfig config, CaptureListener listener);

    /**
     * Tests the connection and returns what it found: the schema the source exposes and a small
     * sample of events.
     */
    ConnectionReport testConnection(CaptureConfig config);

    /** Discovers the streams and fields the source exposes. */
    DiscoveredSchema discoverSchema(CaptureConfig config);
}
