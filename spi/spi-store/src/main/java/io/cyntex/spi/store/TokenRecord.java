package io.cyntex.spi.store;

import java.time.Instant;

/**
 * One issued machine token, as persisted: a public {@code tokenId} handle, the capability grade it
 * grants, the hash of its secret (never the secret itself), whether it has been revoked, and when it
 * was created.
 *
 * <p>{@code scope} is a plain string here on purpose — the storage port depends on the kernel only
 * (rule R2) and cannot see the control layer's grade enum; the control layer maps the string to its
 * grade after a token authenticates. {@code secretHash} is the already-hashed secret, never the raw
 * bearer secret: hashing happens above the store, and the raw secret is shown to its creator once and
 * never persisted, so a store read yields no usable token. Revocation is authoritative here (the
 * {@code revoked} flag), not carried in a stateless credential, so a token can be withdrawn at once.
 *
 * <p>A pure value over {@code java..} only (rule R2): the id, scope and secret hash are required and
 * non-blank, the creation instant is required, and {@code revoked} is a plain flag.
 */
public record TokenRecord(String tokenId, String scope, String secretHash, boolean revoked, Instant createdAt) {

    public TokenRecord {
        requireText(tokenId, "tokenId");
        requireText(scope, "scope");
        requireText(secretHash, "secretHash");
        if (createdAt == null) {
            throw new IllegalArgumentException("token record createdAt must be set");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("token record " + field + " must be non-blank");
        }
    }
}
