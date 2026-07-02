package io.cyntex.app;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The process-termination contract: on SIGTERM/SIGINT the server stops in order, then forces exit
 * code 0 (rather than the signal's default disposition, 128+15 = 143). GracefulShutdown separates
 * that logic — orderly stop, then halt — from the JVM effect (the real shutdown hook and
 * {@code Runtime.halt}), so the logic is unit-tested here with injected seams while the real
 * signal-to-exit-0 path is witnessed black-box by dist-smoke.
 */
class GracefulShutdownTest {

    @Test
    void stopsInOrderThenForcesExitZero() {
        List<String> events = new ArrayList<>();
        AtomicInteger haltCode = new AtomicInteger(-1);
        GracefulShutdown shutdown = new GracefulShutdown(
                () -> events.add("stop"),
                code -> {
                    events.add("halt:" + code);
                    haltCode.set(code);
                });

        shutdown.terminate();

        assertThat(events).containsExactly("stop", "halt:0");
        assertThat(haltCode).hasValue(0);
    }

    @Test
    void haltsEvenWhenTheOrderlyStopThrows() {
        // Runtime.halt runs inside the finally, so a stop that throws still forces exit 0 rather
        // than wedging the hook half-down. In production the halt terminates the JVM inside that
        // finally, so the exception never escapes; with an injected halt that returns, the test
        // sees it propagate — and asserts the halt already fired with 0.
        AtomicInteger haltCode = new AtomicInteger(-1);
        GracefulShutdown shutdown = new GracefulShutdown(
                () -> {
                    throw new IllegalStateException("stop blew up");
                },
                haltCode::set);

        assertThatThrownBy(shutdown::terminate).isInstanceOf(IllegalStateException.class);
        assertThat(haltCode).hasValue(0);
    }
}
