package io.cyntex.spi.store;

import java.util.Objects;

/**
 * The result of a register-if-absent call: the resulting {@link ConnectorRegistration} and whether this
 * call is what stored it. {@code newlyRegistered} is false when an artifact with the same content hash
 * was already registered and the call was a no-op — the signal a register operation reports back as
 * "already registered" rather than storing a second copy.
 */
public record RegistrationOutcome(ConnectorRegistration registration, boolean newlyRegistered) {

    public RegistrationOutcome {
        Objects.requireNonNull(registration, "registration");
    }
}
