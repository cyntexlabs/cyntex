package io.cyntex.control.core;

/**
 * Hashes and verifies passwords. A port so the control layer stays framework-free: the concrete
 * algorithm (an adaptive salted hash) is bound at the assembly root, never referenced here.
 *
 * <p>{@link #hash} produces a self-describing hash string (algorithm, cost and salt embedded), and
 * {@link #matches} verifies a raw password against such a string. A verification must not short-circuit
 * on the stored value's shape: callers rely on it taking roughly the same time whether or not the
 * password is correct, so a login cannot be turned into a timing oracle.
 */
public interface PasswordHasher {

    /** Hashes a raw password into a self-describing hash string safe to persist. */
    String hash(String raw);

    /** Verifies a raw password against a stored hash string; false if it does not match. */
    boolean matches(String raw, String storedHash);
}
