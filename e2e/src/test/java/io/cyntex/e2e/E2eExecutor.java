package io.cyntex.e2e;

import io.cyntex.core.lifecycle.PipelineState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a specification against one tier binding.
 *
 * <p>Waiting is condition-driven and bounded: {@code await} polls a matcher until it holds or the
 * bound expires, and an expired bound reports how long it actually waited alongside what it expected
 * and what it last read. There is no fixed-duration sleep anywhere in a run - a sleep long enough to
 * be reliable is a sleep that wastes that long on every green run, and it is never quite reliable
 * anyway.
 */
public final class E2eExecutor {

    private final TierBinding binding;
    private final PipelineLoader pipelineLoader;
    private final Duration timeout;
    private final Duration pollInterval;

    public E2eExecutor(
            TierBinding binding, PipelineLoader pipelineLoader, Duration timeout, Duration pollInterval) {
        this.binding = binding;
        this.pipelineLoader = pipelineLoader;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    public void execute(Envelope envelope) {
        String pipelineId = pipelineLoader.resolvePipelineId(envelope.pipeline());
        provision(envelope.setup());
        for (Seed seed : envelope.seed()) {
            binding.seed(seed.table(), seed.rows());
        }
        for (Step step : envelope.steps()) {
            execute(step, pipelineId);
        }
    }

    /**
     * Strict order: a resource may not be applied before the connector it names is registered, and a
     * model may not be discovered before the source declaring it exists. The resources themselves go in
     * one batch, because that is the closure the product resolves references within.
     */
    private void provision(Setup setup) {
        setup.connectors().forEach(binding::registerConnector);
        if (!setup.apply().isEmpty()) {
            // A specification that applies nothing gets no apply: an empty batch would be a round trip
            // that asks the product for nothing.
            binding.applyResources(setup.apply());
        }
        setup.discover().forEach(binding::discoverSchema);
    }

    private void execute(Step step, String pipelineId) {
        switch (step) {
            case Step.Lifecycle lifecycle -> binding.drive(pipelineId, lifecycle.verb());
            case Step.Cdc cdc -> binding.cdc(cdc.table(), cdc.op(), cdc.rows());
            case Step.Assertion assertion -> check(assertion.matcher(), pipelineId);
            case Step.Await await -> await(await.matcher(), pipelineId);
        }
    }

    private void check(Matcher matcher, String pipelineId) {
        mismatch(matcher, pipelineId)
                .ifPresent(
                        mismatch -> {
                            throw new AssertionError(mismatch);
                        });
    }

    private void await(Matcher matcher, String pipelineId) {
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        while (true) {
            Optional<String> mismatch = mismatch(matcher, pipelineId);
            if (mismatch.isEmpty()) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError(
                        "timed out after "
                                + Duration.ofNanos(System.nanoTime() - start)
                                + " (bound "
                                + timeout
                                + "); "
                                + mismatch.get());
            }
            sleep(pollInterval);
        }
    }

    /** The reading that falsifies the matcher, or empty when it holds. */
    private Optional<String> mismatch(Matcher matcher, String pipelineId) {
        return switch (matcher) {
            case Matcher.Count count -> countMismatch(count.expected());
            case Matcher.State state -> stateMismatch(state.expected(), pipelineId);
        };
    }

    private Optional<String> countMismatch(Map<TableAlias, Long> expected) {
        List<String> mismatches = new ArrayList<>();
        expected.forEach(
                (table, rows) -> {
                    long actual = binding.count(table);
                    if (actual != rows) {
                        mismatches.add(table + " expected " + rows + ", found " + actual);
                    }
                });
        return mismatches.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", mismatches));
    }

    /**
     * A pipeline that has published no observation yet reads as a mismatch, never as a failure: that is
     * the window between recording an intent and the first convergence pass, and an {@code await} exists
     * to sit through exactly it. The unpublished read is named in the mismatch rather than folded into
     * the states, because "nothing was ever published" and "the wrong state was published" fail for
     * different reasons and send an author looking in different places.
     */
    private Optional<String> stateMismatch(PipelineState expected, String pipelineId) {
        Optional<PipelineState> actual = binding.state(pipelineId);
        if (actual.filter(published -> published == expected).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(
                pipelineId
                        + " expected "
                        + expected
                        + ", found "
                        + actual.map(Object::toString).orElse("no published observation"));
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a condition", e);
        }
    }
}
