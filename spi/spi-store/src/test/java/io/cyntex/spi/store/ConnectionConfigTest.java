package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConnectionConfigTest {

    @Test
    void carriesIdConnectorAndSettings() {
        ConnectionConfig connection = new ConnectionConfig("orders-db", "mysql", Map.of("host", "db"));

        assertThat(connection.id()).isEqualTo("orders-db");
        assertThat(connection.connectorId()).isEqualTo("mysql");
        assertThat(connection.settings()).containsEntry("host", "db");
    }

    @Test
    void nullSettingsBecomeEmpty() {
        assertThat(new ConnectionConfig("orders-db", "mysql", null).settings()).isEmpty();
    }

    @Test
    void settingsAreAnUnmodifiableDefensiveCopy() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("host", "db");
        ConnectionConfig connection = new ConnectionConfig("orders-db", "mysql", source);

        source.put("host", "changed"); // a later mutation of the caller's map must not leak in

        assertThat(connection.settings()).containsEntry("host", "db");
        assertThatThrownBy(() -> connection.settings().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresIdAndConnectorId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionConfig(null, "mysql", Map.of()));
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionConfig("orders-db", null, Map.of()));
    }
}
