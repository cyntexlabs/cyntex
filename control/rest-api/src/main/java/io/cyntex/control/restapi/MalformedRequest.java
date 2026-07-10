package io.cyntex.control.restapi;

import io.cyntex.control.core.ControlError;
import io.cyntex.core.common.CyntexException;

import java.util.Map;

/**
 * Boundary validation for request bodies. A structurally malformed request — a missing required field left
 * null or blank — is a client-attributable, diagnosable error, so it is refused right at the HTTP boundary
 * with a coded {@code control.malformed-request} (a 400) carrying a human {@code reason}, rather than being
 * left to trip a bare invariant crash (a 500) deeper inside a control-core service. The service layer keeps
 * its own bare invariant guards for genuine programmer errors; this layer catches the client's malformed
 * input first, before the service call is made.
 */
final class MalformedRequest {

    private MalformedRequest() {
    }

    /** Refuses the request with {@code reason} unless {@code value} is present; returns it when it is. */
    static <T> T require(T value, String reason) {
        if (value == null) {
            throw refuse(reason);
        }
        return value;
    }

    /** Refuses the request with {@code reason} unless {@code value} is present and non-blank. */
    static void requireText(String value, String reason) {
        if (value == null || value.isBlank()) {
            throw refuse(reason);
        }
    }

    private static CyntexException refuse(String reason) {
        return new CyntexException(ControlError.MALFORMED_REQUEST, Map.of("reason", reason), null);
    }
}
