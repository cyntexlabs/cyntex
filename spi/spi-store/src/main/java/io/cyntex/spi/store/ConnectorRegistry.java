package io.cyntex.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * The connector distribution store: the single truth for which connector artifacts are registered and
 * the bytes to load them from. Registration is content-hash idempotent — the same artifact bytes
 * register once and re-registering them is a no-op — so a startup seed sweep and an explicit runtime
 * register share one path with no duplication. A pure interface (rule R2); it carries no
 * connector-framework or store-driver types.
 */
public interface ConnectorRegistry {

    /**
     * Registers the artifact if its content hash is not already registered (register-if-absent),
     * storing the bytes and a {@link ConnectorRegistration} keyed by that hash. Re-registering bytes
     * whose hash is already stored is a no-op that returns the existing registration with
     * {@code newlyRegistered} false. The content hash is computed from the bytes, so identity is the
     * store's to decide, not the caller's.
     */
    RegistrationOutcome register(String connectorId, String pdkApiVersion, RegistrationSource source, byte[] artifact);

    /** Every registered connector. */
    List<ConnectorRegistration> list();

    /** The artifact bytes stored under a content hash, or empty if none is stored. */
    Optional<byte[]> artifact(String contentHash);
}
