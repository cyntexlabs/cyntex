package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.cyntex.spi.store.ConnectionTestItem.Status;
import org.junit.jupiter.api.Test;

class ConnectionTestItemTest {

    @Test
    void carriesNameStatusAndDiagnostics() {
        ConnectionTestItem item = new ConnectionTestItem(
                "Login",
                Status.FAILED,
                "authentication failed for user 'sync'",
                "SCRAM-SHA-256 negotiation rejected",
                "verify the user exists and the password is current",
                "11000");

        assertThat(item.name()).isEqualTo("Login");
        assertThat(item.status()).isEqualTo(Status.FAILED);
        assertThat(item.message()).isEqualTo("authentication failed for user 'sync'");
        assertThat(item.reason()).isEqualTo("SCRAM-SHA-256 negotiation rejected");
        assertThat(item.solution()).isEqualTo("verify the user exists and the password is current");
        assertThat(item.connectorErrorCode()).isEqualTo("11000");
    }

    @Test
    void optionalDiagnosticsMayBeNull() {
        ConnectionTestItem item = new ConnectionTestItem("Connection", Status.PASSED, null, null, null, null);

        assertThat(item.message()).isNull();
        assertThat(item.reason()).isNull();
        assertThat(item.solution()).isNull();
        assertThat(item.connectorErrorCode()).isNull();
    }

    @Test
    void requiresNameAndStatus() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionTestItem(null, Status.PASSED, null, null, null, null));
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionTestItem("Connection", null, null, null, null, null));
    }
}
