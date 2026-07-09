package io.cyntex.spi.store;

/**
 * One authenticatable principal: a username, the stored password hash, and the role that grants its
 * capability grade. The store persists this shape; the login flow reads it to verify a presented
 * password against {@code passwordHash} and to derive the session scope from {@code role}.
 *
 * <p>{@code role} is a plain string here on purpose — the storage port depends on the kernel only
 * (rule R2) and cannot see the control layer's grade enum; the control layer maps the string to its
 * grade after a successful verification. {@code passwordHash} is the already-hashed credential, never
 * a raw password: hashing happens above the store, and a raw password is never persisted.
 *
 * <p>A pure value over {@code java..} only (rule R2): all three fields are required and non-blank.
 */
public record User(String username, String passwordHash, String role) {

    public User {
        requireText(username, "username");
        requireText(passwordHash, "passwordHash");
        requireText(role, "role");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("user " + field + " must be non-blank");
        }
    }
}
