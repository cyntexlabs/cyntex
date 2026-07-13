package io.cyntex.runtime.scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link LifecycleActuator} double that records the verb calls the converge loop makes, in order,
 * as {@code "<verb>:<pipelineId>"} strings — so a test can assert exactly which data-plane operation
 * each transition drove without a real execution engine.
 */
final class RecordingActuator implements LifecycleActuator {

    private final List<String> calls = new ArrayList<>();

    @Override
    public void start(String pipelineId) {
        calls.add("start:" + pipelineId);
    }

    @Override
    public void pause(String pipelineId) {
        calls.add("pause:" + pipelineId);
    }

    @Override
    public void resume(String pipelineId) {
        calls.add("resume:" + pipelineId);
    }

    @Override
    public void stop(String pipelineId) {
        calls.add("stop:" + pipelineId);
    }

    /** The verbs actuated so far, in order. */
    List<String> calls() {
        return List.copyOf(calls);
    }

    /** Forgets the calls recorded so far, so a test can assert only what a later step actuates. */
    void reset() {
        calls.clear();
    }
}
