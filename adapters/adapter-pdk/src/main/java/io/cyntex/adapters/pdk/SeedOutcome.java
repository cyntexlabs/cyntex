package io.cyntex.adapters.pdk;

import io.cyntex.spi.store.RegistrationOutcome;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One seed artifact's fate in a startup sweep: it went through register-if-absent (with the store's
 * outcome saying whether this sweep stored it or it was already registered), or it failed with a
 * diagnosable reason. Failure is per-artifact by design — one defective jar in the seed directory
 * never stops the rest of the sweep.
 */
public sealed interface SeedOutcome {

    /** The artifact this outcome is about. */
    Path artifact();

    /** The artifact went through register-if-absent; {@code outcome} is the store's answer. */
    record Seeded(Path artifact, RegistrationOutcome outcome) implements SeedOutcome {
        public Seeded {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /** The artifact could not be registered; {@code cause} is the coded refusal or the I/O failure. */
    record Failed(Path artifact, RuntimeException cause) implements SeedOutcome {
        public Failed {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(cause, "cause");
        }
    }
}
