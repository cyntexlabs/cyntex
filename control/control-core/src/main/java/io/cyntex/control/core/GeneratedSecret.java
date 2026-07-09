package io.cyntex.control.core;

/**
 * The freshly minted parts of a machine token: the public {@code tokenId} handle (persisted, and used
 * to look the token up and to revoke it), the one-time {@code secret} (shown to its creator once and
 * never persisted), and {@code secretHash} (the only part stored, so a store read yields no usable
 * token). The presented token string a caller carries binds the id and the secret together.
 */
public record GeneratedSecret(String tokenId, String secret, String secretHash) {

    public GeneratedSecret {
        requireText(tokenId, "tokenId");
        requireText(secret, "secret");
        requireText(secretHash, "secretHash");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("generated secret " + field + " must be non-blank");
        }
    }
}
