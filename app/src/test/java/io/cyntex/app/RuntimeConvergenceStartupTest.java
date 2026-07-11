package io.cyntex.app;

import io.cyntex.runtime.scheduler.PipelineConverger;
import io.cyntex.spi.store.StorePort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runtime convergence loop is wired into startup and gated on the same store switch as the store it
 * reads and writes: enabled, the framework-free converger and its scheduled driver both come up; disabled
 * (a run with no store), neither does.
 */
class RuntimeConvergenceStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StorePort.class, InMemoryStorePort::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(RuntimeConvergenceConfiguration.class);

    @Test
    void enabledBringsUpTheConvergerAndItsDriver() {
        runner.withPropertyValues("cyntex.store.mongo.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PipelineConverger.class);
                    assertThat(context).hasSingleBean(ConvergenceDriver.class);
                });
    }

    @Test
    void disabledBringsUpNeither() {
        runner.withPropertyValues("cyntex.store.mongo.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PipelineConverger.class);
                    assertThat(context).doesNotHaveBean(ConvergenceDriver.class);
                });
    }
}
