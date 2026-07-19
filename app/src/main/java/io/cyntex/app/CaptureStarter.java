package io.cyntex.app;

import io.cyntex.core.event.Envelope;
import io.cyntex.runtime.srs.CaptureRun;
import io.cyntex.runtime.srs.CaptureRunSpec;
import java.util.function.Consumer;

/**
 * The seam by which the capture coordinator starts one source run and gets back a live handle. Its production
 * binding is the capture run unit's {@code start}; keeping it a seam lets the coordinator's handle-lifecycle
 * logic be driven without a running Jet member. The signature matches the run unit exactly, so the binding is
 * a plain method reference.
 */
@FunctionalInterface
interface CaptureStarter {

    /** Starts the source run for {@code spec}, draining any snapshot rows to {@code passthrough}. */
    CaptureRun start(CaptureRunSpec spec, Consumer<Envelope> passthrough);
}
