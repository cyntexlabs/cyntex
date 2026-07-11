package io.cyntex.spi.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * A registration outcome pairs the resulting registration with whether this call is what stored it —
 * false when a same-hash artifact was already registered and the call was a no-op. The registration is
 * required.
 */
class RegistrationOutcomeTest {

    @Test
    void carriesTheRegistrationAndWhetherItWasNewlyStored() {
        ConnectorRegistration registration =
                new ConnectorRegistration("mysql", "abc123", "1.3.5", RegistrationSource.REGISTER);

        assertThat(new RegistrationOutcome(registration, true).newlyRegistered()).isTrue();
        assertThat(new RegistrationOutcome(registration, false).registration()).isEqualTo(registration);
    }

    @Test
    void registrationIsRequired() {
        assertThatNullPointerException().isThrownBy(() -> new RegistrationOutcome(null, true));
    }
}
