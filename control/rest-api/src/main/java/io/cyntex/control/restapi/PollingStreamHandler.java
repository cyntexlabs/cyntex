package io.cyntex.control.restapi;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * The thin streaming primitive shared by the two stream channels: on connect it polls a control-core
 * read face on a fixed cadence and pushes a frame whenever there is something new; on close it cancels
 * the poll. This is deliberately not a general push framework — there is no broker, no topic, no fan-out,
 * no client-driven subscription. Each connection drives exactly one bounded poll loop over one read face,
 * because the observation model is store-backed and eventually consistent (a watch is a re-poll) and the
 * log tail is a node-local ring (a follow is a re-tail).
 *
 * <p>A subclass supplies the poll body — read the current value, compute any frame, {@link #send} it —
 * and keeps its own last-sent bookkeeping in the session attributes so a reconnect starts clean.
 */
abstract class PollingStreamHandler extends TextWebSocketHandler {

    private final TaskScheduler scheduler;
    private final Duration interval;

    /** The live poll task per open session, so the close callback can cancel exactly that session's loop. */
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    PollingStreamHandler(TaskScheduler scheduler, Duration interval) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.interval = Objects.requireNonNull(interval, "interval");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String pipelineId = pipelineIdOf(session);
        ScheduledFuture<?> task =
                scheduler.scheduleWithFixedDelay(() -> pollSafely(session, pipelineId), interval);
        tasks.put(session.getId(), task);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ScheduledFuture<?> task = tasks.remove(session.getId());
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Reads the current value, computes any frame to emit, and {@link #send}s it. Runs on a scheduler
     * thread, once per cadence tick per open session.
     */
    protected abstract void poll(WebSocketSession session, String pipelineId);

    /**
     * Sends one text frame if the session is still open. A send failure means the peer went away
     * mid-send; the loop is left to the close callback to cancel rather than crashing the poll thread.
     */
    protected final void send(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException connectionGone) {
            // The peer closed between the open check and the send; afterConnectionClosed cancels the task.
        }
    }

    private void pollSafely(WebSocketSession session, String pipelineId) {
        try {
            poll(session, pipelineId);
        } catch (RuntimeException transientFailure) {
            // A single bad tick (a read blip) must not suppress every later tick: a scheduled task that
            // throws is not rescheduled, which would silently freeze the stream. Swallow and let the next
            // tick retry. There is no user error channel on a background poll loop.
        }
    }

    /** The pipeline id embedded in a stream path {@code /api/pipelines/{id}/(status/watch|logs/follow)}. */
    private static String pipelineIdOf(WebSocketSession session) {
        String[] segments = session.getUri().getPath().split("/");
        for (int i = 0; i + 1 < segments.length; i++) {
            if (segments[i].equals("pipelines")) {
                return segments[i + 1];
            }
        }
        throw new IllegalStateException("no pipeline id in stream path: " + session.getUri());
    }
}
