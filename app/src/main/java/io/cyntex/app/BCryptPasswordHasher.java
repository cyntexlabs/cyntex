package io.cyntex.app;

import io.cyntex.control.core.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The bcrypt-backed password hasher bound behind the control ring's hasher port at the assembly root.
 * bcrypt is an adaptive salted hash: each {@link #hash} embeds a fresh random salt and the work
 * factor in a self-describing string, so the same password hashes to a different value every time and
 * {@link #matches} reads the salt and cost back out of the stored string to verify — no salt is stored
 * separately, and the cost can be raised over time without breaking existing hashes.
 */
public final class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String raw) {
        return encoder.encode(raw);
    }

    @Override
    public boolean matches(String raw, String storedHash) {
        return encoder.matches(raw, storedHash);
    }
}
