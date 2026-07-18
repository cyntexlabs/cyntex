package io.cyntex.spi.capture;

import io.cyntex.core.event.Envelope;

/**
 * A sink for CDC events: it receives each change event the capture streams. The events are row and
 * schema mutations (ops {@code i} / {@code u} / {@code d} / {@code ddl}).
 */
@FunctionalInterface
public interface CaptureListener {

    /** Called once per captured change event. */
    void onEvent(Envelope event);

    /**
     * Called when the capture stream fails, so the failure a background stream cannot return to its
     * caller is still delivered. The default is a no-op: a listener that does not observe stream health
     * ignores it. Delivered at most once, after which the stream has ended.
     */
    default void onError(Throwable error) {
    }
}
