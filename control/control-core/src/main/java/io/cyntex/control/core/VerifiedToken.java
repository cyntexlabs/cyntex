package io.cyntex.control.core;

/**
 * The verified content of a session token: the subject it was issued to and the capability grade it
 * carries. Produced only by a successful token verification — a token that fails its signature check,
 * is expired, or is malformed yields no verified token at all, so holding one means the claims are
 * authentic.
 */
public record VerifiedToken(String subject, Scope scope) {

    public VerifiedToken {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("verified token subject must be non-blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("verified token scope must be set");
        }
    }
}
