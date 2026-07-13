package io.cyntex.control.restapi;

import io.cyntex.control.core.ConnectorRegisterService;
import io.cyntex.control.core.ConnectorRegistrationReport;
import io.cyntex.core.common.CyntexException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

/**
 * The connector register verb projected onto HTTP: it decodes the uploaded artifact from the request
 * body, drives it through the control-core register service — which ingests it into the distribution
 * store under the audit gate — and returns the surface report. A thin projection with no business logic
 * of its own; the operation is audited to the authenticated caller, read from the guard rather than
 * trusted from the request body. The service speaks control-ring types, so this face never reaches into
 * the storage ports.
 *
 * <p>The artifact arrives base64-encoded in the JSON body, since the CLI shares no filesystem with the
 * server. A missing body / field or non-base64 artifact is refused at the boundary as a coded 400; a bad
 * artifact that decodes but does not load, declares no connector or identity, or collides with a
 * registered id surfaces the register service's coded connector-domain refusal.
 */
@RestController
class ConnectorController {

    private final ConnectorRegisterService registerService;

    ConnectorController(ConnectorRegisterService registerService) {
        this.registerService = registerService;
    }

    @Verb("connector.register")
    @PostMapping("/connectors:register")
    ConnectorRegistrationReport register(
            @RequestBody(required = false) ConnectorRegisterRequest request, HttpServletRequest http) {
        // Refuse a missing / blank-field body at the boundary as a coded 400, rather than letting a null or
        // undecodable artifact trip a bare guard deeper down (a 500).
        ConnectorRegisterRequest body =
                MalformedRequest.require(request, "the request must carry a connector artifact to register");
        MalformedRequest.requireText(body.artifact(), "a base64-encoded `artifact` is required");
        byte[] artifact = decode(body.artifact());
        try {
            return registerService.register(artifact, AuthInterceptor.authenticatedPrincipal(http));
        } catch (CyntexException e) {
            // A connector-domain failure at register is the uploaded artifact's fault (client input): a 400
            // with the coded body. The same code raised on the resolve path (connection test / discovery) is
            // a server-side condition and keeps its 500 — so the status is decided here, where the context is
            // known, not globally by code. A non-connector coded error (e.g. a blocked audit) keeps its status.
            if (e.code().code().startsWith("connector.")) {
                throw new BadRequestCodedException(e);
            }
            throw e;
        }
    }

    /** Decodes the base64 artifact, refusing a non-base64 body field at the boundary as a coded 400. */
    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw MalformedRequest.rejecting("the `artifact` is not valid base64", e);
        }
    }
}
