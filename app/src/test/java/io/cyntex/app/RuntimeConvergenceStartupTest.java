package io.cyntex.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.hazelcast.core.HazelcastInstance;
import io.cyntex.runtime.engine.Engine;
import io.cyntex.runtime.scheduler.LifecycleActuator;
import io.cyntex.runtime.scheduler.PipelineConverger;
import io.cyntex.spi.store.StorePort;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The runtime convergence loop is wired into startup and gated on the same store switch as the store it
 * reads and writes: enabled, the framework-free converger and its scheduled driver both come up; disabled
 * (a run with no store), neither does.
 */
class RuntimeConvergenceStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(StorePort.class, InMemoryStorePort::new)
            .withBean(LifecycleActuator.class, NoOpActuator::new)
            .withBean(Engine.class, () -> new Engine(mock(HazelcastInstance.class)))
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
