package io.cyntex.cli;

/**
 * The outcome of a {@code POST /auth/login} attempt. A login either succeeds with a bearer token, is
 * rejected by the server with a coded reason (a bad credential is refused as {@code control.auth-failed}
 * and reveals nothing about which half was wrong), or the server could not be reached at all. Modelled
 * as a sealed result rather than an exception so the caller renders each branch without try/catch,
 * mirroring the never-throw transport seam.
 */
sealed interface LoginOutcome {

    /** The server verified the credential and minted a session token. */
    record Success(String token) implements LoginOutcome {
    }

    /** The server refused the login with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements LoginOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements LoginOutcome {
    }
}
