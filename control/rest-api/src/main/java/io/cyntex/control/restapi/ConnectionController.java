package io.cyntex.control.restapi;

import io.cyntex.control.core.ConnectionTestReport;
import io.cyntex.control.core.ConnectionTestResultQueryService;
import io.cyntex.control.core.ConnectionTestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connection-test verb projected onto HTTP: the one synchronous control-to-runtime operation the first
 * landing opens. It decodes the connection to test, drives it through the control-core service — which runs
 * the runtime probe under the audit gate and persists the result as the connection's latest — and returns
 * the surface report. A thin projection with no business logic of its own; the operation is audited to the
 * authenticated caller, read from the guard rather than trusted from the request body. The service speaks
 * control-ring types, so this face never reaches into the storage ports.
 *
 * <p>Its read peer projects {@code GET /api/connections/{id}/test-result}: the connection's latest persisted
 * result, or a 404 when it has never been tested.
 */
@RestController
class ConnectionController {

    private final ConnectionTestService testService;
    private final ConnectionTestResultQueryService resultQueryService;

    ConnectionController(ConnectionTestService testService, ConnectionTestResultQueryService resultQueryService) {
        this.testService = testService;
        this.resultQueryService = resultQueryService;
    }

    @Verb("connection.test")
    @PostMapping("/connections:test")
    ConnectionTestReport test(@RequestBody(required = false) ConnectionTestRequest request, HttpServletRequest http) {
        // Refuse a missing / blank-field body at the boundary as a coded 400, rather than letting a null trip
        // the connection-config invariant guard deeper down (a 500).
        ConnectionTestRequest body =
                MalformedRequest.require(request, "the request must carry a connection to test");
        MalformedRequest.requireText(body.id(), "a connection `id` is required");
        MalformedRequest.requireText(body.connectorId(), "a `connectorId` is required");
        return testService.test(body.id(), body.connectorId(), body.settings(),
                AuthInterceptor.authenticatedPrincipal(http));
    }

    @Verb("connection.test-result")
    @GetMapping("/connections/{id}/test-result")
    ResponseEntity<ConnectionTestReport> testResult(@PathVariable("id") String id) {
        // ResponseEntity.of maps a stored result to 200 and a never-tested connection to 404, with no error
        // logic here. The path variable is named explicitly: this build compiles without -parameters, so an
        // inferred name would not resolve at runtime.
        return ResponseEntity.of(resultQueryService.find(id));
    }
}
