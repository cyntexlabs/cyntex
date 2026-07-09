package io.cyntex.control.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a presented credential to a verified token, routing by the credential's own shape so the two
 * authentication mechanisms converge on one {@link VerifiedToken}. A credential carrying the machine-token
 * scheme prefix ({@code cyxt_}) is authenticated against the revocable token store; anything else is
 * verified as a stateless signed session token. So a {@code --token} machine credential and a {@code login}
 * session credential look identical to the dispatcher once resolved — it authorizes the grade, not the
 * mechanism.
 *
 * <p>A bad credential is never an exception, only an absence: a malformed, unknown, revoked, expired or
 * unsigned credential resolves to empty, and the surface turns that into an unauthenticated refusal. The
 * routing is by prefix and therefore exclusive — a machine token is never verified as a session token, and
 * a session token never touches the token store — so neither mechanism can be probed through the other.
 */
public final class CredentialAuthenticator {

    private final TokenService tokenService;
    private final TokenSigner tokenSigner;

    public CredentialAuthenticator(TokenService tokenService, TokenSigner tokenSigner) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.tokenSigner = Objects.requireNonNull(tokenSigner, "tokenSigner");
    }

    /**
     * Authenticates {@code presented} and returns its verified content, or empty when it is absent or does
     * not check out. A credential with the machine-token scheme prefix is looked up — and its revocation
     * checked — in the token store; any other credential is verified as a signed session token.
     */
    public Optional<VerifiedToken> authenticate(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        if (presented.startsWith(TokenService.TOKEN_PREFIX)) {
            return tokenService.authenticate(presented);
        }
        return tokenSigner.verify(presented);
    }
}
