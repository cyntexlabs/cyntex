package io.cyntex.runtime.probe;

import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.SchemaDiscoverer;
import io.cyntex.spi.store.SourceModel;
import java.util.Objects;

/**
 * The runtime-side schema-discovery probe: it fulfils the synchronous control-to-runtime seam by
 * driving the connector-plane schema-discoverer port and returning its normalized model. The
 * discoverer implementation — the one that opens the connector through the PDK — is injected by the
 * app assembly root, so the runtime ring never compiles against an adapter. In the first landing this
 * delegation is a direct in-process call; when the control and runtime roles split, this probe is the
 * engine-side handler the control plane reaches across instances.
 */
public final class DelegatingSchemaDiscoveryProbe implements SchemaDiscoveryProbe {

    private final SchemaDiscoverer discoverer;

    public DelegatingSchemaDiscoveryProbe(SchemaDiscoverer discoverer) {
        this.discoverer = Objects.requireNonNull(discoverer, "discoverer");
    }

    @Override
    public SourceModel discover(ConnectionConfig config) {
        return discoverer.discover(config);
    }
}
