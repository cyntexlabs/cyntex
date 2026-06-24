package io.cyntex.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiscoveryRulesTest {

    @Test
    void fileConnectorsHaveNoCatalog() {
        assertThat(DiscoveryRules.fromGroup(ConnectorGroup.FILE)).isEqualTo(Discovery.NONE);
    }

    @Test
    void databaseAndSaasAndMqCanEnumerate() {
        assertThat(DiscoveryRules.fromGroup(ConnectorGroup.DATABASE)).isEqualTo(Discovery.CATALOG);
        assertThat(DiscoveryRules.fromGroup(ConnectorGroup.SAAS)).isEqualTo(Discovery.CATALOG);
        assertThat(DiscoveryRules.fromGroup(ConnectorGroup.MQ)).isEqualTo(Discovery.CATALOG);
    }

    @Test
    void unknownGroupDefaultsToCatalog() {
        assertThat(DiscoveryRules.fromGroup(ConnectorGroup.OTHER)).isEqualTo(Discovery.CATALOG);
    }
}
