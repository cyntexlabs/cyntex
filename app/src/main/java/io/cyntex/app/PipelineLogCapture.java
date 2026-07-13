package io.cyntex.app;

import ch.qos.logback.classic.Logger;
import io.cyntex.core.logging.LogSink;
import org.slf4j.LoggerFactory;

/**
 * Wires the node-local log capture into the running process: on construction it attaches a
 * {@link PipelineLogAppender} feeding the given sink to the logging backend's root logger, so every
 * pipeline-attributed line logged anywhere in the process is captured. It is {@link AutoCloseable} so the
 * assembly root detaches the appender on shutdown — the appender is not left on the shared root logger
 * across a context restart. This is the one place the framework-managed logging backend and the
 * framework-free sink are bridged; it lives in the assembly ring where touching the backend is allowed.
 */
final class PipelineLogCapture implements AutoCloseable {

    private final Logger root;
    private final PipelineLogAppender appender;

    PipelineLogCapture(LogSink sink) {
        this.appender = new PipelineLogAppender(sink);
        this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
    }
}
