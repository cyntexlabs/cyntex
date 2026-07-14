package io.cyntex.control.core;

import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;

/**
 * The surface-facing report of a connector registration: the control ring's own projection of the
 * storage-port {@link RegistrationOutcome}, so the HTTP and CLI faces render a control-ring type and
 * never reach into the storage ports. It carries the registered connector's identity — id, content
 * hash, declared PDK API version (null when the artifact declares none) and the recorded source — and
 * {@code newlyRegistered}: false when the identical artifact was already registered and this call was a
 * no-op. On that no-op branch the identity is the artifact's first registration, so a caller reads
 * "already registered" from the flag rather than the source. An immutable value.
 */
public record ConnectorRegistrationReport(
        String connectorId,
        String contentHash,
        String pdkApiVersion,
        RegistrationSource source,
        boolean newlyRegistered) {

    /** Projects a storage-port registration outcome onto the surface report. */
    public static ConnectorRegistrationReport from(RegistrationOutcome outcome) {
        ConnectorRegistration registration = outcome.registration();
        return new ConnectorRegistrationReport(
                registration.connectorId(),
                registration.contentHash(),
                registration.pdkApiVersion(),
                registration.source(),
                outcome.newlyRegistered());
    }
}
