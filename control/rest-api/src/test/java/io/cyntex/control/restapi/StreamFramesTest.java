package io.cyntex.control.restapi;

import io.cyntex.control.core.PipelineLogs;
import io.cyntex.control.core.PipelineStatus;
import io.cyntex.core.lifecycle.PipelineState;
import io.cyntex.core.logging.LogLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stream frame encoders: a status or logs frame rendered to the same compact JSON the one-shot
 * {@code GET} read faces return, so the CLI decodes a streamed frame with the exact decoder it uses for
 * a polled read. The message escaping is delegated to the core JSON writer, so an arbitrary log message
 * is rendered safely.
 */
class StreamFramesTest {

    @Test
    void statusFrameCarriesTheIdAndStateNameLikeTheReadFace() {
        String frame = StreamFrames.status(new PipelineStatus("orders", PipelineState.RUNNING));
        assertThat(frame).isEqualTo("{\"pipelineId\":\"orders\",\"state\":\"RUNNING\"}");
    }

    @Test
    void logsFrameCarriesTheIdAndLinesLikeTheReadFace() {
        PipelineLogs logs = new PipelineLogs("orders", List.of(
                new LogLine(1_700_000_000_000L, "INFO", "submitted job")));
        String frame = StreamFrames.logs(logs);
        assertThat(frame).isEqualTo(
                "{\"pipelineId\":\"orders\",\"lines\":[{\"timestampMillis\":1700000000000,"
                        + "\"level\":\"INFO\",\"message\":\"submitted job\"}]}");
    }

    @Test
    void logsFrameEscapesAnArbitraryMessage() {
        PipelineLogs logs = new PipelineLogs("p", List.of(
                new LogLine(1L, "WARN", "quote\" and newline\n")));
        String frame = StreamFrames.logs(logs);
        assertThat(frame).contains("\"message\":\"quote\\\" and newline\\n\"");
    }

    @Test
    void logsFrameOfNoLinesCarriesAnEmptyArray() {
        String frame = StreamFrames.logs(new PipelineLogs("p", List.of()));
        assertThat(frame).isEqualTo("{\"pipelineId\":\"p\",\"lines\":[]}");
    }
}
