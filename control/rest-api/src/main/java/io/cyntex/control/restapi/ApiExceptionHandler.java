package io.cyntex.control.restapi;

import io.cyntex.control.core.ControlError;
import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.CyntexException;
import io.cyntex.messages.MessageCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.TreeMap;

/**
 * Projects a coded first-party error onto a structured HTTP response. A {@link CyntexException} — a
 * user-facing, diagnosable failure — becomes a {@code {code, params, message}} body: the canonical code
 * string, the named arguments, and the message rendered from them through the shared catalog. The status
 * is chosen from the code: a client input error (a {@code dsl.*} validation failure or a
 * {@code control.malformed-request} refused at the boundary) is a 400; the authentication codes map to
 * 401 / 403 / 409; a lifecycle verb on an unknown pipeline is a 404 and a forbidden transition or stale
 * revision is a 409; a status / metrics / snapshot read of a pipeline that has published no observation is a
 * 404; any other coded error keeps the structured body but answers 500, since the surface has no
 * client-attributable mapping for it yet. That mapping is the seam later slices extend as more
 * client-attributable codes land.
 *
 * <p>Only {@link CyntexException} is handled here. A programmer error / invariant violation (a bare NPE or
 * {@code IllegalStateException}) is left to crash into the container's default 500 — it must never be
 * laundered into a pretty coded body that hides the defect.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private final MessageCatalog catalog;

    ApiExceptionHandler(MessageCatalog catalog) {
        this.catalog = catalog;
    }

    @ExceptionHandler(CyntexException.class)
    ResponseEntity<ApiError> handle(CyntexException e) {
        MessageCatalog.Rendered rendered = catalog.render(e.code(), e.args());
        // Sorted so the params render identically regardless of throw-site order (a stable machine contract).
        ApiError body = new ApiError(e.code().code(), new TreeMap<>(e.args()), rendered.message());
        return ResponseEntity.status(statusFor(e.code())).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleMalformed(HttpMessageNotReadableException ignored) {
        return handle(new CyntexException(
                ControlError.MALFORMED_REQUEST,
                Map.of("reason", "the JSON body does not match the request shape"),
                null));
    }

    /**
     * A coded error a verb boundary has attributed to the client's request answers 400 while rendering the
     * same coded body. This is how a domain code that is a client error in one verb's context but a
     * server-side failure in another gets the right status: the verb that has the context wraps it here,
     * rather than {@link #statusFor} guessing globally from the code alone.
     */
    @ExceptionHandler(BadRequestCodedException.class)
    ResponseEntity<ApiError> handle(BadRequestCodedException e) {
        MessageCatalog.Rendered rendered = catalog.render(e.code(), e.args());
        ApiError body = new ApiError(e.code().code(), new TreeMap<>(e.args()), rendered.message());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * The HTTP status for a coded error. The authentication codes are client-attributable and map to the
     * usual auth statuses: no / invalid credential and a rejected login are 401, an under-scoped or
     * non-loopback caller is 403, and a bootstrap channel that has already closed is a 409 state conflict.
     * A client input error — a {@code dsl.*} validation failure or a {@code control.malformed-request} refused
     * at the boundary — is a 400. A lifecycle verb on a pipeline that was never applied is a 404; a transition
     * the state machine forbids, or a start/resume at a stale revision, is a 409 state conflict. A status /
     * metrics / snapshot read of a pipeline that has published no observation is likewise a 404. Any other coded
     * error is a server-side failure (500) that still carries the structured body — never a bare, uncoded crash.
     */
    static HttpStatus statusFor(CyntexErrorCode code) {
        return switch (code.code()) {
            case "control.auth-failed", "control.unauthenticated" -> HttpStatus.UNAUTHORIZED;
            case "control.forbidden", "control.bootstrap-forbidden" -> HttpStatus.FORBIDDEN;
            case "control.bootstrap-closed" -> HttpStatus.CONFLICT;
            case "source.id-mismatch" -> HttpStatus.BAD_REQUEST;
            case "source.not-found" -> HttpStatus.NOT_FOUND;
            case "source.already-exists", "source.in-use" -> HttpStatus.CONFLICT;
            case "source.version-conflict" -> HttpStatus.PRECONDITION_FAILED;
            case "source.precondition-required" -> HttpStatus.PRECONDITION_REQUIRED;
            case "connector.not-found" -> HttpStatus.NOT_FOUND;
            // A request refused at the HTTP boundary as structurally malformed is a client input error, like dsl.*.
            case "control.malformed-request" -> HttpStatus.BAD_REQUEST;
            // A lifecycle verb on a pipeline that was never applied is a 404; a verb the state machine forbids
            // from the current state, or a start/resume at a stale revision, is a 409 state conflict.
            case "lifecycle.unknown-pipeline" -> HttpStatus.NOT_FOUND;
            case "lifecycle.illegal-transition", "lifecycle.incompatible-revision" -> HttpStatus.CONFLICT;
            // A status / metrics / snapshot read of a pipeline that has published no observation is a 404: the
            // observation resource does not exist yet, like a get of an unknown artifact.
            case "monitor.no-observation" -> HttpStatus.NOT_FOUND;
            default -> switch (domainOf(code.code())) {
                case "dsl" -> HttpStatus.BAD_REQUEST;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
        };
    }

    /** The {@code <domain>} segment of a canonical {@code <domain>.<symbol>} code. */
    private static String domainOf(String code) {
        int dot = code.indexOf('.');
        return dot < 0 ? code : code.substring(0, dot);
    }
}
