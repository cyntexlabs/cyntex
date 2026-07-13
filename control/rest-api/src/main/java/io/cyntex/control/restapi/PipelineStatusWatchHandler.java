package io.cyntex.control.restapi;

import io.cyntex.control.core.PipelineObservationQueryService;
import io.cyntex.control.core.PipelineStatus;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.lifecycle.PipelineState;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.Objects;

/**
 * The status watch channel: streams a pipeline's lifecycle state, then the next state whenever it
 * changes. It polls the same store-backed status read the one-shot {@code GET} uses, so it is a re-poll
 * over the eventually-consistent observation doc, not a live push. Only a changed state emits a frame,
 * so an idle pipeline is quiet on the wire. A pipeline that has published no observation yet emits
 * nothing and keeps polling — a watch may be started before the pipeline is first observed.
 */
final class PipelineStatusWatchHandler extends PollingStreamHandler {

    /** Session attribute holding the last state already sent to this watcher, so only changes are pushed. */
    private static final String LAST_STATE = "cyntex.stream.lastState";

    private final PipelineObservationQueryService observations;

    PipelineStatusWatchHandler(
            PipelineObservationQueryService observations, TaskScheduler scheduler, Duration interval) {
        super(scheduler, interval);
        this.observations = Objects.requireNonNull(observations, "observations");
    }

    @Override
    protected void poll(WebSocketSession session, String pipelineId) {
        PipelineStatus status;
        try {
            status = observations.status(pipelineId);
        } catch (CyntexException noObservationYet) {
            // No observation published for this pipeline yet: nothing to stream, keep polling.
            return;
        }
        PipelineState previous = (PipelineState) session.getAttributes().get(LAST_STATE);
        if (!status.state().equals(previous)) {
            send(session, StreamFrames.status(status));
            session.getAttributes().put(LAST_STATE, status.state());
        }
    }
}
