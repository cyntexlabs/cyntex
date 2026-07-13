package io.cyntex.control.restapi;

import io.cyntex.control.core.ControlError;
import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.CyntexException;
import io.cyntex.core.common.Severity;
import io.cyntex.core.dsl.DslError;
import io.cyntex.messages.MessageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void aMalformedRequestIsABadRequestWithACodedRenderedBody() {
        // A request refused at the boundary as structurally malformed is a client input error like dsl.*: a 400
        // with a coded body whose reason is substituted into the catalog template, not left as the bare code.
        CyntexException e = new CyntexException(
                ControlError.MALFORMED_REQUEST, Map.of("reason", "a `username` is required"), null);

        ResponseEntity<ApiError> response = handler.handle(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = response.getBody();
        assertThat(body.code()).isEqualTo("control.malformed-request");
        assertThat(body.message()).contains("a `username` is required").isNotEqualTo("control.malformed-request");
        assertThat(body.params()).containsEntry("reason", "a `username` is required");
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
    void aConnectorDomainCodeDefaultsToServerSideRegardlessOfWhereItWasRaised() {
        // No connector code is a client error by its code alone: the same connector code raised on the
        // resolve path (connection test / discovery) is a server-side failure — e.g. a registered connector
        // that fails to link at test time is connector.load-failed but not the caller's fault. So statusFor
        // keeps every connector.* at 500; the register verb, which knows the uploaded artifact is the
        // client's, opts specific failures into a 400 via BadRequestCodedException instead of the code table.
        for (String code : List.of(
                "connector.load-failed",
                "connector.no-connector-class",
                "connector.ambiguous-connector-class",
                "connector.spec-not-found",
                "connector.spec-invalid",
                "connector.registration-conflict",
                "connector.not-registered",
                "connector.ambiguous-registration")) {
            assertThat(ApiExceptionHandler.statusFor(new StubCode(code))).as(code)
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Test
    void aBoundaryAttributedCodedErrorIsABadRequestPreservingTheCode() {
        // A verb boundary that knows a domain code is the client's fault in its context wraps it as a
        // BadRequestCodedException: a 400 that still renders the underlying coded body (here a connector
        // artifact refused at register), even though the code defaults to 500 globally.
        CyntexException connectorFailure = new CyntexException(new StubCode("connector.spec-invalid"),
                Map.of("artifact", "bad.jar", "spec", "spec.json", "detail", "the spec is not valid JSON"), null);

        ResponseEntity<ApiError> response = handler.handle(new BadRequestCodedException(connectorFailure));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("connector.spec-invalid");
        assertThat(response.getBody().message()).isNotBlank().isNotEqualTo("connector.spec-invalid");
        assertThat(response.getBody().params()).containsEntry("artifact", "bad.jar");
    }

    @Test
    void theCanonicalCodeCrossesTheBoundaryAsAStringNotAnEnum() {
        CyntexException e = new CyntexException(DslError.MALFORMED_YAML, Map.of("detail", "bad token"), null);

        ApiError body = handler.handle(e).getBody();

        // the wire identity is the canonical code string (ApiError.code is a String); the enum never crosses
        assertThat(body.code()).isEqualTo("dsl.malformed-yaml");
    }

    /** A coded error whose only relevant facet is its canonical code string — enough to exercise statusFor. */
    private record StubCode(String code) implements CyntexErrorCode {
        @Override
        public Severity severity() {
            return Severity.ERROR;
        }

        @Override
        public Set<String> placeholders() {
            return Set.of();
        }
    }
}
