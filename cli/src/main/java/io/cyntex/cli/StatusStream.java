package io.cyntex.cli;

/**
 * A sink for a status watch stream: called once per state the server streams — the current state, then
 * each change. The CLI carries no shared control type (rule R6), so the state travels as its wire string.
 */
@FunctionalInterface
interface StatusStream {

    /** The pipeline's current lifecycle state (its wire name, e.g. {@code RUNNING}). */
    void state(String pipelineId, String state);
}
