package io.cyntex.control.restapi;

import io.cyntex.control.core.Authorization;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.Operation;
import io.cyntex.control.core.VerifiedToken;
import io.cyntex.core.common.CyntexException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Authenticates and grade-checks a streaming websocket handshake, the counterpart to the {@code /api}
 * {@link AuthInterceptor} for the two thin stream channels. A watch / follow stream is category-2 CLI
 * sugar riding a read operation ({@code pipeline.status} / {@code pipeline.logs}); it invents no new
 * operation, so the handshake is gated by the grade of that underlying read. The credential travels in
 * the same {@code Authorization: Bearer <credential>} header a one-shot read uses.
 *
 * <p>A refused handshake fails with an HTTP status rather than an upgraded connection: no credential (or
 * an unknown / revoked / expired one) is a 401, and a known caller lacking the read grade is a 403. Both
 * are set on the handshake response and stop the upgrade. The read is unaudited and names no principal,
 * so nothing is stashed for the handler.
 */
final class PipelineStreamHandshakeInterceptor implements HandshakeInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final CredentialAuthenticator credentials;
    private final Operation operation;

    PipelineStreamHandshakeInterceptor(CredentialAuthenticator credentials, Operation operation) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Optional<VerifiedToken> credential = credentials.authenticate(bearerCredential(request.getHeaders()));
        if (credential.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Authorization.require(credential.get(), operation);
        } catch (CyntexException forbidden) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // A read stream is unaudited and names no principal; nothing to record or clean up.
    }

    /** The credential from an {@code Authorization: Bearer <credential>} header, or {@code null} if absent. */
    private static String bearerCredential(HttpHeaders headers) {
        String header = headers.getFirst(HttpHeaders.AUTHORIZATION);
        // The auth-scheme name is case-insensitive (RFC 7235), so accept "bearer" in any case.
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }
}
