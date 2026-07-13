package io.cyntex.cli;

import java.util.List;

/**
 * A sink for a logs follow stream: called once per batch of newly appended log lines the server streams,
 * oldest to newest. The CLI mirrors the server's line shape independently (rule R6).
 */
@FunctionalInterface
interface LogStream {

    /** A batch of newly appended log lines for the pipeline, oldest to newest. */
    void lines(String pipelineId, List<RemoteLogLine> lines);
}
