package io.cyntex.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the embedded Hazelcast member ({@code cyntex.hz.*}). Follows the configuration
 * layering: packaged defaults, overridable from the external conf file or the environment.
 */
@ConfigurationProperties(prefix = "cyntex.hz")
class HazelcastProperties {

    private String clusterName = "cyntex";
    private final Jet jet = new Jet();

    String getClusterName() {
        return clusterName;
    }

    void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    Jet getJet() {
        return jet;
    }

    /** Jet engine knobs. */
    static class Jet {

        /** Cooperative worker thread count; {@code null} keeps the engine default (CPU cores). */
        private Integer cooperativeThreadCount;

        Integer getCooperativeThreadCount() {
            return cooperativeThreadCount;
        }

        void setCooperativeThreadCount(Integer cooperativeThreadCount) {
            this.cooperativeThreadCount = cooperativeThreadCount;
        }
    }
}
