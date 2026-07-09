package io.cyntex.control.core;

import java.time.Instant;

/**
 * The safe, listable view of an issued machine token: its public id, the grade it grants, whether it
 * is revoked, and when it was created. Carries no secret and no secret hash — listing tokens never
 * exposes anything a holder could authenticate with.
 */
public record TokenInfo(String tokenId, Scope scope, boolean revoked, Instant createdAt) {
}
