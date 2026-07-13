package io.cyntex.core.logging;

import java.util.Objects;

/**
 * One captured operational log line, node-local and in-memory. This is the shape a control read face
 * projects when tailing a pipeline's logs: the moment the line was logged, its level, and the
 * already-formatted message. It carries the pipeline attribution implicitly — the sink keys lines by
 * pipeline id, so the id is not repeated on every line.
 *
 * @param timestampMillis when the line was logged, epoch milliseconds
 * @param level           the log level (for example {@code INFO}, {@code WARN}, {@code ERROR})
 * @param message         the formatted log message
 */
public record LogLine(long timestampMillis, String level, String message) {

    public LogLine {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
    }
}
