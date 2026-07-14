package io.cyntex.spi.store;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The content hash that keys a connector artifact in the distribution store: the lower-hex SHA-256 of
 * the artifact bytes. One definition so every side that hashes an artifact — the store that files it,
 * the registrar that pre-checks an id conflict against it — agrees on the identity, rather than each
 * re-deriving it and risking a silent divergence.
 */
public final class ContentHash {

    private ContentHash() {
    }

    /** The lower-hex SHA-256 of {@code bytes}. */
    public static String of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a required JDK algorithm; its absence is a broken runtime, not a user error.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
