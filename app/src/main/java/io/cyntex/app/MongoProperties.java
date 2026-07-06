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

    /**
     * The explicit downgrade permitting a plaintext store connection. Off by default: TLS to the
     * store is mandatory, so a configuration that would reach it over plaintext is refused rather
     * than silently allowed. Turn it on only to deliberately reach a plaintext store (a local
     * development one, say).
     */
    private boolean allowInsecure = false;

    /**
     * An optional path to a PEM CA certificate to trust for the store's TLS handshake — the local
     * development self-signed chain. Unset falls back to the JVM default trust store.
     */
    private String tlsCaFile;

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

    public boolean isAllowInsecure() {
        return allowInsecure;
    }

    public void setAllowInsecure(boolean allowInsecure) {
        this.allowInsecure = allowInsecure;
    }

    public String getTlsCaFile() {
        return tlsCaFile;
    }

    public void setTlsCaFile(String tlsCaFile) {
        this.tlsCaFile = tlsCaFile;
    }

    public Duration getServerSelectionTimeout() {
        return serverSelectionTimeout;
    }

    public void setServerSelectionTimeout(Duration serverSelectionTimeout) {
        this.serverSelectionTimeout = serverSelectionTimeout;
    }
}
