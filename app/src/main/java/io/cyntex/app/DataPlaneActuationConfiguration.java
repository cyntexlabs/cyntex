package io.cyntex.app;

import com.hazelcast.core.HazelcastInstance;
import io.cyntex.runtime.engine.Engine;
import io.cyntex.runtime.scheduler.LifecycleActuator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the data-plane actuation binding into the assembly root: the Jet {@link Engine} over the
 * embedded member, the topology source, and the {@link LifecycleActuator} that joins the converge loop
 * to the engine. Split from the convergence loop's wiring so the loop can be brought up in isolation
 * over a stand-in actuator, and gated on the same store switch as the convergence it serves — a run with
 * no store drives no pipeline, so it needs neither the loop nor the engine binding.
 */
@Configuration
@ConditionalOnProperty(prefix = "cyntex.store.mongo", name = "enabled", matchIfMissing = true)
class DataPlaneActuationConfiguration {

    @Bean
    Engine engine(HazelcastInstance hazelcastMember) {
        return new Engine(hazelcastMember);
    }

    @Bean
    DagSource dagSource() {
        return new PlaceholderDagSource();
    }

    @Bean
    LifecycleActuator lifecycleActuator(Engine engine, DagSource dagSource) {
        return new EngineLifecycleActuator(engine, dagSource);
    }
}
