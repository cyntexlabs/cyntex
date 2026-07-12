package io.cyntex.app;

import io.cyntex.adapters.pdk.SeedConnectorSweep;
import io.cyntex.adapters.pdk.SeedOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Sweeps the connector seed directory once at startup and logs each artifact's fate. Failures are
 * per-artifact and already contained in the sweep's outcomes, so a defective seed jar is a warning
 * in the log, never a failed boot; what does register is exactly what the release shipped in the
 * directory. A seed path that is misconfigured (exists but is not a directory) or a directory the
 * process cannot list is deliberately not contained: that fault hides every shipped connector at
 * once, so it fails the boot loudly instead of degrading silently.
 */
final class SeedSweepRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SeedSweepRunner.class);

    private final SeedConnectorSweep sweep;
    private final Path seedDir;

    SeedSweepRunner(SeedConnectorSweep sweep, Path seedDir) {
        this.sweep = Objects.requireNonNull(sweep, "sweep");
        this.seedDir = Objects.requireNonNull(seedDir, "seedDir");
    }

    @Override
    public void run(ApplicationArguments args) {
        report(sweep.sweep(seedDir));
    }

    /** One log line per artifact: seeded, already registered, or not registered with the reason. */
    void report(List<SeedOutcome> outcomes) {
        for (SeedOutcome outcome : outcomes) {
            switch (outcome) {
                case SeedOutcome.Seeded seeded when seeded.outcome().newlyRegistered() ->
                        LOG.info("Seeded connector '{}' ({}) from {}",
                                seeded.outcome().registration().connectorId(),
                                seeded.outcome().registration().contentHash(),
                                seeded.artifact());
                case SeedOutcome.Seeded seeded ->
                        LOG.info("Seed connector '{}' ({}) is already registered; left {} as is",
                                seeded.outcome().registration().connectorId(),
                                seeded.outcome().registration().contentHash(),
                                seeded.artifact());
                case SeedOutcome.Failed failed ->
                        LOG.warn("Seed artifact {} was not registered", failed.artifact(), failed.cause());
            }
        }
    }
}
