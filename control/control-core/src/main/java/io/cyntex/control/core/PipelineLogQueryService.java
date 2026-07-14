package io.cyntex.control.core;

import io.cyntex.core.logging.LogSink;

import java.util.Objects;

/**
 * The pipeline logs read side. It tails the node-local log sink for one pipeline — the process's own
 * captured log output, keyed by pipeline id. This is the read face that is not store-backed: it reads
 * an in-process sink directly rather than the published observation doc, because logs are node-local
 * and not fanned into a shared store. A read of a pipeline with no captured lines is a benign empty
 * tail, never a coded error.
 */
public final class PipelineLogQueryService {

    private final LogSink logs;

    public PipelineLogQueryService(LogSink logs) {
        this.logs = Objects.requireNonNull(logs, "logs");
    }

    /** The pipeline's most recent log lines, oldest to newest; empty when it has logged nothing here. */
    public PipelineLogs logs(String pipelineId) {
        Objects.requireNonNull(pipelineId, "pipelineId");
        return new PipelineLogs(pipelineId, logs.tail(pipelineId));
    }
}
