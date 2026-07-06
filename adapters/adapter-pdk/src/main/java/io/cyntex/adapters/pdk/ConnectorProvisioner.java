package io.cyntex.adapters.pdk;

/**
 * Resolves a connector id to the {@link ConnectorRef} that says where to load it from. The capture and
 * sink ports are constructed with one; the resolution itself — the seed directory today, the connector
 * distribution store later — is out of this module's scope.
 */
@FunctionalInterface
public interface ConnectorProvisioner {

    /** The ref for {@code connectorId}, or throws if the id resolves to no connector. */
    ConnectorRef resolve(String connectorId);
}
