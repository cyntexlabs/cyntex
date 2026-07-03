package io.cyntex.app;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operational logging framework: free-form diagnostic logs for operators (process health,
 * lifecycle, troubleshooting), wired through Spring Boot's logback support and distinct from the
 * coded error-code system. This locks the framework shape — a console appender plus a rolling file
 * appender, the reserved MDC attribution slots spliced into both formats, and a size+time rolling
 * policy with a bounded retention that operators can override from configuration.
 */
class OperationalLoggingTest {

    /**
     * Boots a minimal server context so Spring Boot applies {@code logback-spring.xml}, then returns
     * the resulting root appenders. The store is disabled (this is about logging, not connectivity)
     * and the log directory is a temp dir so the run writes nothing under the module.
     */
    private List<Appender<?>> rootAppendersAfterBoot(Path logDir, String... extraProps) {
        List<String> props = new ArrayList<>(List.of(
                "cyntex.store.mongo.enabled=false",
                "cyntex.log.dir=" + logDir));
        props.addAll(List.of(extraProps));
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(Bootstrap.class)
                .web(WebApplicationType.NONE)
                .properties(props.toArray(String[]::new))
                .run()) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            List<Appender<?>> appenders = new ArrayList<>();
            root.iteratorForAppenders().forEachRemaining(appenders::add);
            return appenders;
        }
    }

    private static RollingFileAppender<?> fileAppender(List<Appender<?>> appenders) {
        return appenders.stream()
                .filter(RollingFileAppender.class::isInstance)
                .map(a -> (RollingFileAppender<?>) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rolling file appender wired on the root logger"));
    }

    private static ConsoleAppender<?> consoleAppender(List<Appender<?>> appenders) {
        return appenders.stream()
                .filter(ConsoleAppender.class::isInstance)
                .map(a -> (ConsoleAppender<?>) a)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no console appender wired on the root logger"));
    }

    private static String encoderPattern(ch.qos.logback.core.OutputStreamAppender<?> appender) {
        return ((PatternLayoutEncoder) appender.getEncoder()).getPattern();
    }

    @Test
    void wiresBothAConsoleAndARollingFileAppender(@TempDir Path logDir) {
        List<Appender<?>> appenders = rootAppendersAfterBoot(logDir);
        assertThat(appenders).anyMatch(ConsoleAppender.class::isInstance);
        assertThat(appenders).anyMatch(RollingFileAppender.class::isInstance);
    }

    @Test
    void fileAppenderRollsBySizeAndTime(@TempDir Path logDir) {
        RollingFileAppender<?> file = fileAppender(rootAppendersAfterBoot(logDir));
        assertThat(file.getRollingPolicy()).isInstanceOf(SizeAndTimeBasedRollingPolicy.class);
    }

    @Test
    void formatReservesTheMdcAttributionSlots(@TempDir Path logDir) {
        List<Appender<?>> appenders = rootAppendersAfterBoot(logDir);
        // Slots are reserved in the format now and populated by later runtime work; they render empty
        // until then, but the format must already carry them so it never has to change to gain them.
        // Both destinations must carry the slots: the console is the primary operator-visible stream,
        // the file is the durable one — a regression in either format is a defect.
        assertThat(encoderPattern(fileAppender(appenders)))
                .as("file format")
                .contains("%X{role")
                .contains("%X{component")
                .contains("%X{pipeline_id");
        assertThat(encoderPattern(consoleAppender(appenders)))
                .as("console format")
                .contains("%X{role")
                .contains("%X{component")
                .contains("%X{pipeline_id");
    }

    @Test
    void retentionIsBoundedByDefault(@TempDir Path logDir) {
        RollingFileAppender<?> file = fileAppender(rootAppendersAfterBoot(logDir));
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) file.getRollingPolicy();
        assertThat(policy.getMaxHistory()).isEqualTo(14);
    }

    @Test
    void retentionIsOverridableFromConfiguration(@TempDir Path logDir) {
        RollingFileAppender<?> file =
                fileAppender(rootAppendersAfterBoot(logDir, "cyntex.log.max-history=3"));
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) file.getRollingPolicy();
        assertThat(policy.getMaxHistory()).isEqualTo(3);
    }
}
