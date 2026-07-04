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
}
