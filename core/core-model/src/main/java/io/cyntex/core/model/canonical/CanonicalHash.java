package io.cyntex.core.model.canonical;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The content hash of a canonical form: lower-hex SHA-256 over the canonical UTF-8 bytes. The hash
 * is a function of the canonical serialization alone, so re-emitting an unchanged resource re-hashes
 * identically — this is the idempotency key that lets a re-apply of unchanged content be a no-op.
 *
 * <p>Identity stays the top-level id; this hash decides no-op vs a new revision, never storage
 * keying. SHA-256 via the built-in JDK provider is native-image safe (no reflection, no runtime
 * classpath scanning), so it holds across the fat-jar server and the native CLI alike.
 */
public final class CanonicalHash {

    private CanonicalHash() {
    }

    /** Lower-hex SHA-256 of {@code canonicalForm}'s UTF-8 bytes (64 characters). */
    public static String of(String canonicalForm) {
        Objects.requireNonNull(canonicalForm, "canonicalForm");
        byte[] digest = sha256().digest(canonicalForm.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; its absence is a broken runtime, not a user error.
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
