package io.cyntex.control.restapi;

import io.cyntex.control.core.Authorization;
import io.cyntex.control.core.ControlError;
import io.cyntex.control.core.CredentialAuthenticator;
import io.cyntex.control.core.Operation;
import io.cyntex.control.core.OperationRegistry;
import io.cyntex.control.core.VerifiedToken;
import io.cyntex.core.common.CyntexException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The authentication and authorization gate on the {@code /api} verb surface. Every {@code /api} handler
 * is the projection of one registered operation (it carries {@link Verb}); before the handler runs, this
 * interceptor resolves that operation, authenticates the presented credential, and checks the credential's
 * grade against the operation's. Two coded refusals may result, each rendered as a structured body by the
 * shared exception advice:
 *
 * <ul>
 *   <li><b>{@code control.unauthenticated} (401)</b> — no credential was presented, or the one presented is
 *       malformed, unknown, revoked, expired or unsigned. The refusal is identical for every reason, so it
 *       cannot be used to probe which credentials exist.
 *   <li><b>{@code control.forbidden} (403)</b> — a known, authenticated caller lacks the grade the operation
 *       requires.
 * </ul>
 *
 * <p>Registered on {@code /api/**} only, so it never sees the anonymous liveness probe or the
 * pre-authentication entry points (login, bootstrap) — those live outside {@code /api} by design. The
 * credential is read from an {@code Authorization: Bearer <credential>} header, where the credential is
 * either a machine token or a session token; {@link CredentialAuthenticator} routes it by shape.
 */
final class AuthInterceptor implements HandlerInterceptor {

    /**
     * Request attribute the interceptor stashes the authenticated caller's subject under, so a handler can
     * name the real caller when it audits a write. Set only after authentication and authorization pass, so
     * a handler that reads it runs only for an authorized caller.
     */
    static final String PRINCIPAL_ATTRIBUTE = "io.cyntex.control.principal";

    private static final String BEARER_PREFIX = "Bearer ";

    private final OperationRegistry registry;
    private final CredentialAuthenticator credentials;

    AuthInterceptor(OperationRegistry registry, CredentialAuthenticator credentials) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            // Not a controller method (e.g. a static-resource handler); there is no operation to authorize.
            return true;
        }
        Verb verb = method.getMethodAnnotation(Verb.class);
        if (verb == null) {
            // Every /api handler must project a registered verb (the endpoint-derivation gate asserts it).
            // One reaching here without @Verb is a wiring bug — fail closed with a bare invariant violation
            // rather than serving it unauthenticated.
            throw new IllegalStateException("an /api handler carries no @Verb: " + method.getMethod());
        }
        // An unregistered verb id is likewise a wiring bug, not user input: resolve() bare-crashes on it.
        Operation op = registry.resolve(verb.value());

        Optional<VerifiedToken> credential = credentials.authenticate(bearerCredential(request));
        if (credential.isEmpty()) {
            throw new CyntexException(ControlError.UNAUTHENTICATED, Map.of(), null);
        }
        Authorization.require(credential.get(), op);
        // Expose the authenticated subject to the handler so an audited verb records the real caller.
        request.setAttribute(PRINCIPAL_ATTRIBUTE, credential.get().subject());
        return true;
    }

    /** The credential from an {@code Authorization: Bearer <credential>} header, or {@code null} if absent. */
    private static String bearerCredential(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        // The auth-scheme name is case-insensitive (RFC 7235), so accept "bearer" in any case.
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length());
    }
}
