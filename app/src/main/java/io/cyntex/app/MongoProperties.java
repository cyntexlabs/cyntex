package io.cyntex.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The externalized store connection settings, bound from {@code cyntex.store.mongo.*}. Defaults
 * target a local single-node replica-set; the distribution's {@code conf/} and environment
 * variables override them (build once, run anywhere).
 */
@ConfigurationProperties("cyntex.store.mongo")
public class MongoProperties {

    /** Whether to connect to the store at startup. Off only where the store is intentionally absent. */
    private boolean enabled = true;

    /** A {@code mongodb://} connection string carrying the host(s), default database and replica-set. */
    private String uri = "mongodb://localhost:27017/cyntex?replicaSet=rs0";

    /** Whether to require TLS to the store. Off for local runs; the store implementation makes it mandatory. */
    private boolean tls = false;

    /** How long connection verification waits for a reachable server before reporting the store unreachable. */
    private Duration serverSelectionTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean tls) {
        this.tls = tls;
    }

    public Duration getServerSelectionTimeout() {
        return serverSelectionTimeout;
    }

    public void setServerSelectionTimeout(Duration serverSelectionTimeout) {
        this.serverSelectionTimeout = serverSelectionTimeout;
    }
}
