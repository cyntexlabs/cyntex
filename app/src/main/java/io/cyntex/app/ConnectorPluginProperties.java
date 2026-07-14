package io.cyntex.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * The connector runtime settings, bound from {@code cyntex.connectors.*}. The plugins directory is the
 * on-disk cache the provisioner stages resolved connector artifacts into, content-addressed by hash and
 * reused across resolves. The seed directory is where a release ships connector jars; it is swept once
 * at startup, each jar going through the same register-if-absent path as an explicit register, and a
 * deployment without one is valid. Both default to directories under the working directory, which the
 * distribution's {@code conf/} and environment variables override (build once, run anywhere).
 */
@ConfigurationProperties("cyntex.connectors")
public class ConnectorPluginProperties {

    /** The on-disk cache directory for staged connector artifacts (a file per content hash). */
    private Path pluginsDir = Path.of("plugins");

    /** The seed directory swept at startup: every {@code *.jar} in it is registered if absent. */
    private Path seedDir = Path.of("connectors");

    public Path getPluginsDir() {
        return pluginsDir;
    }

    public void setPluginsDir(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }

    public Path getSeedDir() {
        return seedDir;
    }

    public void setSeedDir(Path seedDir) {
        this.seedDir = seedDir;
    }
}
