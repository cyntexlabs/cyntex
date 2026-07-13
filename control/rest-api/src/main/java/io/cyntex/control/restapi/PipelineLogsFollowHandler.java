package io.cyntex.control.restapi;

import io.cyntex.control.core.PipelineLogQueryService;
import io.cyntex.control.core.PipelineLogs;
import io.cyntex.core.logging.LogLine;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * The logs follow channel: streams a pipeline's node-local log tail, then only the lines appended since
 * the last frame ({@code tail -f}). It polls the same node-local sink the one-shot {@code GET} tails and
 * sends the delta {@link LogDelta#newLines computed} against what the follower has already been sent, so
 * an idle pipeline is quiet on the wire. Because the sink is a bounded ring, a burst may evict lines
 * before the follower polls; the delta then re-tails rather than dropping the gap, so a follower may see
 * a line twice, never lose one.
 */
final class PipelineLogsFollowHandler extends PollingStreamHandler {

    /** Session attribute holding the tail window last observed, so the next tick sends only what is new. */
    private static final String LAST_TAIL = "cyntex.stream.lastTail";

    private final PipelineLogQueryService logs;

    PipelineLogsFollowHandler(PipelineLogQueryService logs, TaskScheduler scheduler, Duration interval) {
        super(scheduler, interval);
        this.logs = Objects.requireNonNull(logs, "logs");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void poll(WebSocketSession session, String pipelineId) {
        List<LogLine> current = logs.logs(pipelineId).lines();
        List<LogLine> previous = (List<LogLine>) session.getAttributes().getOrDefault(LAST_TAIL, List.of());
        List<LogLine> fresh = LogDelta.newLines(previous, current);
        if (!fresh.isEmpty()) {
            send(session, StreamFrames.logs(new PipelineLogs(pipelineId, fresh)));
        }
        session.getAttributes().put(LAST_TAIL, current);
    }
}
