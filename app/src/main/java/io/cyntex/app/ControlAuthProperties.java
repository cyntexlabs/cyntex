package io.cyntex.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The externalized control-plane authentication settings, bound from {@code cyntex.control.auth.*}. The
 * distribution's {@code conf/} and environment variables override the defaults (build once, run anywhere).
 */
@ConfigurationProperties("cyntex.control.auth")
public class ControlAuthProperties {

    /**
     * The secret the session-token signer signs and verifies with. Left unset, the server mints a fresh
     * random secret at startup — fine for a single node, but session tokens then do not survive a restart
     * and are not shared across nodes, so a multi-instance or restart-stable deployment sets one here.
     */
    private String jwtSecret;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }
}
