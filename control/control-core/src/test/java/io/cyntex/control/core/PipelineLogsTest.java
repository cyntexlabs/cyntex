package io.cyntex.control.core;

import io.cyntex.core.logging.LogLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineLogsTest {

    private static LogLine line(String message) {
        return new LogLine(0L, "INFO", message);
    }

    @Test
    void holdsThePipelineIdAndLines() {
        PipelineLogs logs = new PipelineLogs("orders_sync", List.of(line("a"), line("b")));

        assertThat(logs.pipelineId()).isEqualTo("orders_sync");
        assertThat(logs.lines()).extracting(LogLine::message).containsExactly("a", "b");
    }

    @Test
    void nullLinesBecomeEmpty() {
        // A pipeline that has logged nothing is a benign empty tail, never a null: null reads as empty.
        PipelineLogs logs = new PipelineLogs("p1", null);

        assertThat(logs.lines()).isEmpty();
    }

    @Test
    void linesAreDefensivelyCopiedAndImmutable() {
        List<LogLine> source = new ArrayList<>(List.of(line("a")));
        PipelineLogs logs = new PipelineLogs("p1", source);

        source.clear();

        assertThat(logs.lines()).extracting(LogLine::message).containsExactly("a");
        assertThatThrownBy(() -> logs.lines().add(line("x"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresPipelineId() {
        assertThatNullPointerException().isThrownBy(() -> new PipelineLogs(null, List.of()));
    }
}
