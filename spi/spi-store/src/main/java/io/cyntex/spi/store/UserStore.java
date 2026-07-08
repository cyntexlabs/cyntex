package io.cyntex.spi.store;

import java.util.Optional;

/**
 * The user store port: the persistence contract behind human authentication. A pure interface over
 * the core ring only (rule R2); a store backend (a database adapter) implements it.
 *
 * <p>A standalone port, not part of the aggregate storage port — it mirrors the audit-log port,
 * which is likewise reached on its own rather than through the artifact / state / catalog surface.
 */
public interface UserStore {

    /** Returns the stored user for {@code username}, or empty if no such user exists. */
    Optional<User> find(String username);

    /**
     * Persists {@code user}, upserting by username: saving a user whose username already exists
     * replaces the stored record in place rather than creating a second one.
     */
    void save(User user);
}
