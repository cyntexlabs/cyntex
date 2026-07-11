package io.cyntex.spi.store;

/**
 * Derives the raw capabilities a connector declares by driving the connector's {@code
 * registerCapabilities} and reading back the ids it registers — without opening any connection. The
 * bridge reports what the connector registered and maps nothing; turning the ids into source modes and
 * sink capability is the catalog's job. The port carries no connector-framework types.
 *
 * <p>Only a failure that prevents derivation from running at all (the connector cannot be loaded or is
 * level-incompatible) surfaces as a coded exception.
 */
public interface CapabilityDeriver {

    /** Derives the capabilities the connector named by {@code connectorId} registers. */
    ConnectorCapabilities derive(String connectorId);
}
