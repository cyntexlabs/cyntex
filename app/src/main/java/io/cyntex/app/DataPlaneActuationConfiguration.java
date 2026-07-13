package io.cyntex.app;

import com.hazelcast.core.HazelcastInstance;
import io.cyntex.runtime.engine.Engine;
import io.cyntex.runtime.scheduler.LifecycleActuator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(DataPlaneActuationConfiguration.class);

    @Bean
    Engine engine(HazelcastInstance hazelcastMember) {
        return new Engine(hazelcastMember);
    }

    @Bean
    DagSource dagSource() {
        // Surface the pre-integration state rather than letting it be silent: a started pipeline runs a
        // stand-in idle job, so its lifecycle is real but it moves no data until the capture and transform
        // planes are wired and the real per-pipeline topology replaces this.
        LOG.warn("Data-plane topology is a placeholder: a started pipeline runs a stand-in idle job and "
                + "moves no data until the capture and transform planes are wired");
        return new PlaceholderDagSource();
    }

    @Bean
    LifecycleActuator lifecycleActuator(Engine engine, DagSource dagSource) {
        return new EngineLifecycleActuator(engine, dagSource);
    }
}
