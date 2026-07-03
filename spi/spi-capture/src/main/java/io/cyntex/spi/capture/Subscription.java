package io.cyntex.spi.capture;

/**
 * A handle on a running CDC capture. Closing it stops the capture and releases the source; closing
 * is idempotent.
 */
public interface Subscription extends AutoCloseable {

    /** Stops the capture and releases the source. Idempotent. */
    @Override
    void close();
}
