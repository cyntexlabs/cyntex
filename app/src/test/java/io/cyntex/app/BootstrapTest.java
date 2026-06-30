package io.cyntex.app;

import io.cyntex.adapters.mongostore.MongoStoreAdapter;
import io.cyntex.adapters.pdk.PdkAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembly root: the Spring application context starts and stops cleanly (validating Spring Boot
 * on JDK 21), and the root records the adapter bridges it wires — the R7 exemption made real, since
 * the app is the only non-adapter module permitted to reference the adapters ring.
 */
class BootstrapTest {

    @Test
    void springApplicationContextStartsAndStops() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Bootstrap.class)
                .web(WebApplicationType.NONE)
                .run()) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    @Test
    void assemblyRootRecordsTheAdapterBridges() {
        assertThat(Bootstrap.adapterBridges())
                .containsExactly(PdkAdapter.class, MongoStoreAdapter.class);
    }
}
