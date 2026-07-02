package io.cyntex.app;

import java.util.function.IntConsumer;

/**
 * Owns process termination for the server. On SIGTERM/SIGINT the JVM runs the installed shutdown
 * hook, which stops the server in order (closing the application context — the embedded Hazelcast
 * member leaves, the store connection closes) and then forces exit code 0.
 *
 * <p>Why force the exit code: the JVM's default disposition for a signal-terminated run is
 * {@code 128 + signum} (143 for SIGTERM), even after shutdown hooks execute cleanly. An operator
 * asking the process to stop expects a clean {@code 0}. Calling {@code Runtime.halt(0)} from inside
 * the hook preempts that default disposition — {@code halt} is a standard, public API, so no
 * internal signal handling is needed.
 *
 * <p>The orderly stop and the halt are injected seams ({@code Runnable} + {@code IntConsumer}) so
 * the logic is unit-testable; production wires {@code context::close} and {@code Runtime.getRuntime()::halt}.
 * The real signal-to-exit-0 path is witnessed black-box by dist-smoke.
 */
final class GracefulShutdown {

    private final Runnable orderlyStop;
    private final IntConsumer halt;

    GracefulShutdown(Runnable orderlyStop, IntConsumer halt) {
        this.orderlyStop = orderlyStop;
        this.halt = halt;
    }

    /** Registers the termination hook so SIGTERM/SIGINT trigger an orderly stop then exit 0. */
    void install() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::terminate, "cyntex-shutdown"));
    }

    /**
     * Stops the server in order, then forces exit code 0. The halt runs in the finally clause, so a
     * stop that throws still terminates the process rather than wedging the hook and leaving the JVM
     * to exit with the signal's default disposition.
     */
    void terminate() {
        try {
            orderlyStop.run();
        } finally {
            halt.accept(0);
        }
    }
}
