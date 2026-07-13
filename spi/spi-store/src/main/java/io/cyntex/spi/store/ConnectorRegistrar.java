package io.cyntex.spi.store;

/**
 * Ingests a connector artifact's bytes into the distribution store: it introspects the artifact for
 * the identity and PDK API version it declares, refuses an artifact whose bytes differ from one already
 * registered under the same connector id, and otherwise register-if-absent stores it. The control ring
 * drives this port to serve the runtime register operation; its PDK-touching implementation lives in
 * the adapters ring. A pure interface (rule R2); it carries no connector-framework types.
 *
 * <p>The startup seed sweep reaches the same underlying ingestion by an on-disk path; this port is the
 * bytes-off-the-wire entry the register operation uses, since a remote CLI shares no filesystem with
 * the server.
 */
public interface ConnectorRegistrar {

    /**
     * Registers the artifact carried by {@code artifact} under the {@link RegistrationSource} recorded,
     * returning the resulting registration and whether it was newly stored. A re-register of identical
     * bytes is a no-op that reports {@code newlyRegistered} false; a different artifact under an
     * already-registered connector id is refused with a coded connector-domain exception.
     */
    RegistrationOutcome register(byte[] artifact, RegistrationSource source);
}
