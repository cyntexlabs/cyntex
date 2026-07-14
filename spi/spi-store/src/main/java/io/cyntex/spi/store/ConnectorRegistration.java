package io.cyntex.spi.store;

import java.util.Objects;

/**
 * A registered connector's identity in the distribution store: its connector id, the content hash of
 * its artifact (the idempotent registration key and the key its bytes are stored under), the PDK API
 * version its artifact declares, and where the registration originated. An immutable value carrying no
 * connector-framework or store-driver types.
 *
 * <p>{@code connectorId}, {@code contentHash} and {@code source} are always present; {@code
 * pdkApiVersion} is null when the artifact declares none.
 */
public record ConnectorRegistration(
        String connectorId, String contentHash, String pdkApiVersion, RegistrationSource source) {

    public ConnectorRegistration {
        Objects.requireNonNull(connectorId, "connectorId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(source, "source");
    }
}
