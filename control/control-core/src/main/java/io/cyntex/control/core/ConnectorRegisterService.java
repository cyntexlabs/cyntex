package io.cyntex.control.core;

import io.cyntex.spi.store.ConnectorRegistrar;
import io.cyntex.spi.store.ContentHash;
import io.cyntex.spi.store.RegistrationSource;

import java.util.Objects;

/**
 * The control plane's register verb: it ingests an uploaded connector artifact into the distribution
 * store through the registrar port, and returns what was registered. The whole operation runs under the
 * audit gate — registering executable connector code is an audited write, so an audit record is written
 * before the artifact is ingested and the operation is refused if that write fails (no audit, no
 * execute).
 *
 * <p>The audited resource is the artifact, keyed by its content hash: it identifies exactly the bytes
 * being registered and is computed here without classloading, so the record is written before the
 * registrar opens the artifact at all. The registrar refuses an artifact that does not load, declares no
 * identity, or collides with a different artifact already registered under its id — those coded
 * connector-domain failures propagate out of here uncaught. The stored outcome keeps the storage-port
 * shape; the returned report is the control ring's own projection, so the faces never reach into the
 * storage ports.
 */
public final class ConnectorRegisterService {

    private final ConnectorRegistrar registrar;
    private final AuditGate auditGate;

    public ConnectorRegisterService(ConnectorRegistrar registrar, AuditGate auditGate) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.auditGate = Objects.requireNonNull(auditGate, "auditGate");
    }

    /**
     * Registers the uploaded {@code artifact} bytes under the audit gate and returns the surface report.
     * {@code principal} is the authenticated subject the audit record is attributed to; the audited
     * resource is the artifact's content hash. A re-register of identical bytes reports
     * {@code newlyRegistered} false; a coded connector-domain failure (unloadable / no identity /
     * id conflict) propagates uncaught.
     */
    public ConnectorRegistrationReport register(byte[] artifact, String principal) {
        Objects.requireNonNull(artifact, "artifact");
        return auditGate.dispatch(
                ControlOperations.CONNECTOR_REGISTER,
                new AuditContext(principal, ContentHash.of(artifact)),
                () -> ConnectorRegistrationReport.from(
                        registrar.register(artifact, RegistrationSource.REGISTER)));
    }
}
