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
 * bound expires, and a expired bound fails with what was expected and what was actually read. There
 * is no fixed-duration sleep anywhere in a run - a sleep long enough to be reliable is a sleep that
 * wastes that long on every green run, and it is never quite reliable anyway.
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

    /** Strict order: a resource may not be applied before the connector it names is registered. */
    private void provision(Setup setup) {
        setup.connectors().forEach(binding::registerConnector);
        setup.apply().forEach(binding::applyResource);
        setup.discover().forEach(binding::discoverSchema);
    }

    private void execute(Step step, String pipelineId) {
        switch (step) {
            case Step.Lifecycle lifecycle -> binding.drive(pipelineId, lifecycle.verb());
            case Step.Cdc cdc -> binding.cdc(cdc.table(), cdc.op(), cdc.rows());
            case Step.Assertion assertion -> check(assertion.matcher());
            case Step.Await await -> await(await.matcher());
        }
    }

    private void check(Matcher matcher) {
        mismatch(matcher)
                .ifPresent(
                        mismatch -> {
                            throw new AssertionError(mismatch);
                        });
    }

    private void await(Matcher matcher) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<String> mismatch = mismatch(matcher);
            if (mismatch.isEmpty()) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("timed out after " + timeout + "; " + mismatch.get());
            }
            sleep(pollInterval);
        }
    }

    /** The reading that falsifies the matcher, or empty when it holds. */
    private Optional<String> mismatch(Matcher matcher) {
        return switch (matcher) {
            case Matcher.Count count -> countMismatch(count.expected());
            case Matcher.State state -> stateMismatch(state.expected());
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

    private Optional<String> stateMismatch(Map<String, PipelineState> expected) {
        List<String> mismatches = new ArrayList<>();
        expected.forEach(
                (id, state) -> {
                    PipelineState actual = binding.state(id);
                    if (actual != state) {
                        mismatches.add(id + " expected " + state + ", found " + actual);
                    }
                });
        return mismatches.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", mismatches));
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
