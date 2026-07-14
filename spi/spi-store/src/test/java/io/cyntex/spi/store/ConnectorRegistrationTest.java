package io.cyntex.spi.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * A connector registration is an immutable identity value: connector id, artifact content hash, the
 * declared PDK API version (which may be absent), and where the registration originated. The three
 * identity parts are required; the api version is optional.
 */
class ConnectorRegistrationTest {

    @Test
    void carriesItsIdentityHashApiVersionAndSource() {
        ConnectorRegistration registration =
                new ConnectorRegistration("mysql", "abc123", "1.3.5", RegistrationSource.REGISTER);

        assertThat(registration.connectorId()).isEqualTo("mysql");
        assertThat(registration.contentHash()).isEqualTo("abc123");
        assertThat(registration.pdkApiVersion()).isEqualTo("1.3.5");
        assertThat(registration.source()).isEqualTo(RegistrationSource.REGISTER);
    }

    @Test
    void anAbsentApiVersionIsAllowed() {
        ConnectorRegistration registration =
                new ConnectorRegistration("mysql", "abc123", null, RegistrationSource.SEED);

        assertThat(registration.pdkApiVersion()).isNull();
    }

    @Test
    void connectorIdIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectorRegistration(null, "abc123", "1.3.5", RegistrationSource.SEED));
    }

    @Test
    void contentHashIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectorRegistration("mysql", null, "1.3.5", RegistrationSource.SEED));
    }

    @Test
    void sourceIsRequired() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectorRegistration("mysql", "abc123", "1.3.5", null));
    }
}
