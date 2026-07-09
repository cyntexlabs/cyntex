package io.cyntex.control.restapi;

import io.cyntex.control.core.ControlError;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.dsl.DslError;
import io.cyntex.messages.MessageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The coded-error to HTTP mapping in isolation (no container): a {@link CyntexException} becomes a
 * structured {@code {code, params, message}} body at a status chosen by the code's domain — a client
 * input error ({@code dsl.*}) is a 400, and a coded server-side failure keeps the structured body but
 * answers 500 (distinct from an uncoded programmer bug, which the advice never catches at all).
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(MessageCatalog.bundled());

    @Test
    void aClientInputErrorIsABadRequestWithACodedRenderedBody() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("field", "snapshot_mode");
        args.put("path", "options.snapshot_mode");
        CyntexException e = new CyntexException(DslError.UNKNOWN_FIELD, args, null);

        ResponseEntity<ApiError> response = handler.handle(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = response.getBody();
        assertThat(body.code()).isEqualTo("dsl.unknown-field");
        // the message is rendered from the catalog with the named args substituted, not the bare code
        assertThat(body.message()).isEqualTo("Unknown field 'snapshot_mode' at options.snapshot_mode.");
        assertThat(body.params()).containsEntry("field", "snapshot_mode").containsEntry("path", "options.snapshot_mode");
    }

    @Test
    void aCodedServerSideFailureKeepsTheCodedBodyButAnswersServerError() {
        CyntexException e = new CyntexException(ControlError.AUDIT_BLOCKED, Map.of("op", "artifact.apply"), null);

        ResponseEntity<ApiError> response = handler.handle(e);

        // a coded error the surface has no client-attributable mapping for is a server-side failure (500),
        // yet still carries the structured coded body — it is not a bare, uncoded crash
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("control.audit-blocked");
        assertThat(response.getBody().message()).isNotBlank().isNotEqualTo("control.audit-blocked");
    }

    @Test
    void aMissingOrRejectedCredentialIsUnauthorized() {
        // Both the interceptor's "no valid credential" and the login flow's own rejection map to 401.
        assertThat(handler.handle(new CyntexException(ControlError.UNAUTHENTICATED, Map.of(), null)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handle(new CyntexException(ControlError.AUTH_FAILED, Map.of(), null)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnderScopedOrNonLoopbackCallerIsForbidden() {
        assertThat(handler.handle(new CyntexException(
                ControlError.FORBIDDEN, Map.of("op", "artifact.apply", "required", "write"), null)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(handler.handle(new CyntexException(ControlError.BOOTSTRAP_FORBIDDEN, Map.of(), null)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAlreadyClosedBootstrapChannelIsAConflict() {
        ResponseEntity<ApiError> response =
                handler.handle(new CyntexException(ControlError.BOOTSTRAP_CLOSED, Map.of(), null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("control.bootstrap-closed");
    }

    @Test
    void theCanonicalCodeCrossesTheBoundaryAsAStringNotAnEnum() {
        CyntexException e = new CyntexException(DslError.MALFORMED_YAML, Map.of("detail", "bad token"), null);

        ApiError body = handler.handle(e).getBody();

        // the wire identity is the canonical code string (ApiError.code is a String); the enum never crosses
        assertThat(body.code()).isEqualTo("dsl.malformed-yaml");
    }
}
