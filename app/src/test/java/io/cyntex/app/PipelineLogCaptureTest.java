package io.cyntex.app;

import ch.qos.logback.classic.Logger;
import io.cyntex.core.logging.LogLine;
import io.cyntex.core.logging.RingBufferLogSink;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The capture attaches a {@link PipelineLogAppender} to the logging backend's root logger, so any
 * pipeline-attributed line logged anywhere in the process is fed to the node-local sink; closing it
 * detaches the appender, so it stops capturing (and does not leak across a context restart).
 */
class PipelineLogCaptureTest {

    private static final String MDC_KEY = "pipeline_id";

    @Test
    void capturesPipelineLinesWhileAttachedAndStopsAfterClose() {
        RingBufferLogSink sink = new RingBufferLogSink(8, 8);
        // A logger that propagates to root, so a line logged here reaches the root-attached appender.
        Logger logger = (Logger) LoggerFactory.getLogger("test.capture");

        try (PipelineLogCapture capture = new PipelineLogCapture(sink)) {
            MDC.put(MDC_KEY, "pl-cap");
            logger.info("while attached");
            MDC.remove(MDC_KEY);
        }

        // After close the appender is detached, so a further pipeline line is not captured.
        MDC.put(MDC_KEY, "pl-cap");
        logger.info("after close");
        MDC.remove(MDC_KEY);

        assertThat(sink.tail("pl-cap")).extracting(LogLine::message).containsExactly("while attached");
    }
}
