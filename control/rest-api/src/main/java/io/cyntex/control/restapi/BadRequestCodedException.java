package io.cyntex.control.restapi;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.CyntexException;

import java.util.Map;

/**
 * A coded error the HTTP boundary attributes to the client's request — a 400 — while preserving the
 * underlying domain code and its params so the body renders identically to any other coded error. It
 * exists because the same domain code can be a client error in one verb's context and a server-side
 * failure in another: a connector-domain failure is the uploaded artifact's fault at register (a 400),
 * but a registered connector's fault at connection test / discovery (a 500). The status is therefore
 * decided at the verb boundary that has the context, not globally from the code alone.
 */
final class BadRequestCodedException extends RuntimeException {

    private final transient CyntexErrorCode code;
    private final transient Map<String, Object> args;

    BadRequestCodedException(CyntexException cause) {
        super(cause);
        this.code = cause.code();
        this.args = cause.args();
    }

    CyntexErrorCode code() {
        return code;
    }

    Map<String, Object> args() {
        return args;
    }
}
