package io.cyntex.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * The connector runtime settings, bound from {@code cyntex.connectors.*}. The plugins directory is the
 * on-disk cache the provisioner stages resolved connector artifacts into, content-addressed by hash and
 * reused across resolves; it defaults to {@code plugins} under the working directory, which the
 * distribution's {@code conf/} and environment variables override (build once, run anywhere).
 */
@ConfigurationProperties("cyntex.connectors")
public class ConnectorPluginProperties {

    /** The on-disk cache directory for staged connector artifacts (a file per content hash). */
    private Path pluginsDir = Path.of("plugins");

    public Path getPluginsDir() {
        return pluginsDir;
    }

    public void setPluginsDir(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }
}
