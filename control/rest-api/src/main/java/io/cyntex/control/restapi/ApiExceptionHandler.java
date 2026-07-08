package io.cyntex.control.restapi;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.CyntexException;
import io.cyntex.messages.MessageCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.TreeMap;

/**
 * Projects a coded first-party error onto a structured HTTP response. A {@link CyntexException} — a
 * user-facing, diagnosable failure — becomes a {@code {code, params, message}} body: the canonical code
 * string, the named arguments, and the message rendered from them through the shared catalog. The status
 * is chosen from the code's domain: a client input error ({@code dsl.*}) is a 400; any other coded error
 * keeps the structured body but answers 500, since the surface has no client-attributable mapping for it
 * yet. That mapping is the seam later slices extend as client-attributable codes land (the auth layer
 * maps its codes to 401 / 403, and so on).
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

    /**
     * The HTTP status for a coded error, chosen by its domain: a client input error is a 400; any other
     * coded error is a server-side failure (500) that still carries the structured body.
     */
    static HttpStatus statusFor(CyntexErrorCode code) {
        String domain = domainOf(code.code());
        return switch (domain) {
            case "dsl" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** The {@code <domain>} segment of a canonical {@code <domain>.<symbol>} code. */
    private static String domainOf(String code) {
        int dot = code.indexOf('.');
        return dot < 0 ? code : code.substring(0, dot);
    }
}
