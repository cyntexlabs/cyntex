package io.cyntex.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * The token store port: the persistence contract behind machine-token authentication. A pure
 * interface over the core ring only (rule R2); a store backend (a database adapter) implements it.
 *
 * <p>A standalone port, not part of the aggregate storage port — it mirrors the user and audit-log
 * ports, which are likewise reached on their own rather than through the artifact / state / catalog
 * surface. A machine token is looked up by its public id on every authenticated request, so
 * revocation is authoritative here in the store rather than carried in a stateless credential.
 */
public interface TokenStore {

    /** Persists a newly issued token record, keyed by its unique token id. */
    void save(TokenRecord record);

    /** Returns the stored token for {@code tokenId}, or empty if no such token exists. */
    Optional<TokenRecord> find(String tokenId);

    /**
     * Marks the token with {@code tokenId} revoked so it can no longer authenticate. Idempotent:
     * revoking an unknown or already-revoked token is a no-op, not an error. The record is kept
     * (marked, never deleted), so a revoked token still lists and its audit history stays intact.
     */
    void revoke(String tokenId);

    /** All stored token records, including revoked ones, for listing. */
    List<TokenRecord> list();
}
