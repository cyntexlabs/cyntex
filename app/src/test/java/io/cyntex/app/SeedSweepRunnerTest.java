package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.cyntex.adapters.pdk.ConnectorArtifactRegistrar;
import io.cyntex.adapters.pdk.ConnectorIntrospector;
import io.cyntex.adapters.pdk.SeedConnectorSweep;
import io.cyntex.adapters.pdk.SeedOutcome;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.ConnectorRegistry;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The startup seed sweep at the assembly root: a defective artifact in the seed directory becomes a
 * warning in the log, never a failed boot; what was seeded or already registered is reported; a
 * deployment without a seed directory starts silently. The sweep and registration semantics
 * themselves are proven at the adapter — this locks the boot-side containment and reporting.
 */
class SeedSweepRunnerTest {

    private final ListAppender<ILoggingEvent> logLines = new ListAppender<>();

    @BeforeEach
    void captureRunnerLog() {
        logLines.start();
        runnerLogger().addAppender(logLines);
    }

    @AfterEach
    void releaseRunnerLog() {
        runnerLogger().detachAppender(logLines);
        logLines.stop();
    }

    @Test
    void aDefectiveSeedArtifactIsAWarningNotAFailedBoot(@TempDir Path seedDir) throws Exception {
        Files.write(seedDir.resolve("garbage.jar"), new byte[] {0x13, 0x37});
        RecordingRegistry registry = new RecordingRegistry();
        SeedSweepRunner runner = new SeedSweepRunner(sweepOver(registry), seedDir);

        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        assertThat(registry.registered).isEmpty();
        assertThat(logLines.list).hasSize(1);
        assertThat(logLines.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logLines.list.get(0).getFormattedMessage()).contains("garbage.jar");
    }

    @Test
    void aMissingSeedDirectoryStartsSilently(@TempDir Path dir) {
        SeedSweepRunner runner = new SeedSweepRunner(sweepOver(new RecordingRegistry()), dir.resolve("absent"));

        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        assertThat(logLines.list).isEmpty();
    }

    @Test
    void reportSaysWhatWasSeededAndWhatWasAlreadyRegistered(@TempDir Path dir) {
        SeedSweepRunner runner = new SeedSweepRunner(sweepOver(new RecordingRegistry()), dir);
        ConnectorRegistration orders =
                new ConnectorRegistration("orders", "feed", "1.3.5", RegistrationSource.SEED);

        runner.report(List.of(
                new SeedOutcome.Seeded(dir.resolve("orders.jar"), new RegistrationOutcome(orders, true)),
                new SeedOutcome.Seeded(dir.resolve("orders-copy.jar"), new RegistrationOutcome(orders, false))));

        assertThat(logLines.list).hasSize(2);
        assertThat(logLines.list.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(logLines.list.get(0).getFormattedMessage())
                .contains("Seeded connector 'orders'").contains("orders.jar");
        assertThat(logLines.list.get(1).getLevel()).isEqualTo(Level.INFO);
        assertThat(logLines.list.get(1).getFormattedMessage())
                .contains("already registered").contains("orders-copy.jar");
    }

    private static Logger runnerLogger() {
        return (Logger) LoggerFactory.getLogger(SeedSweepRunner.class);
    }

    private static SeedConnectorSweep sweepOver(ConnectorRegistry registry) {
        return new SeedConnectorSweep(new ConnectorArtifactRegistrar(registry, new ConnectorIntrospector()));
    }

    /** An in-memory register-if-absent stand-in that records what reached it. */
    private static final class RecordingRegistry implements ConnectorRegistry {

        final List<ConnectorRegistration> registered = new ArrayList<>();

        @Override
        public RegistrationOutcome register(
                String connectorId, String pdkApiVersion, RegistrationSource source, byte[] artifact) {
            ConnectorRegistration registration =
                    new ConnectorRegistration(connectorId, "hash-" + registered.size(), pdkApiVersion, source);
            registered.add(registration);
            return new RegistrationOutcome(registration, true);
        }

        @Override
        public List<ConnectorRegistration> list() {
            return List.copyOf(registered);
        }

        @Override
        public Optional<byte[]> artifact(String contentHash) {
            return Optional.empty();
        }
    }
}
