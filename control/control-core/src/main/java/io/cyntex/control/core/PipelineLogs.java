package io.cyntex.control.core;

import io.cyntex.core.logging.LogLine;

import java.util.List;
import java.util.Objects;

/**
 * The logs read face: the recent operational log lines of one pipeline, oldest to newest. Unlike the
 * other observation reads, this is node-local — it tails the process's own log output rather than a
 * published, cross-node observation. Empty when the pipeline has logged nothing on this node (or is
 * unknown here): the absence of log lines is normal, never an error.
 */
public record PipelineLogs(String pipelineId, List<LogLine> lines) {

    public PipelineLogs {
        Objects.requireNonNull(pipelineId, "pipelineId");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
