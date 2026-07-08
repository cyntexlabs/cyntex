package io.cyntex.control.restapi;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The anonymous liveness probe for load balancers and readiness checks. It is pure HTTP, not the
 * projection of a control operation: it carries no {@code @Verb}, is not registered, and needs no
 * authentication — a probe must answer before anyone can log in. It is a plain {@code @Controller}
 * (not {@code @RestController}) precisely so it stays at the root, outside the {@code /api} prefix
 * that sweeps up every verb endpoint.
 *
 * <p>It answers 200 while this process serves HTTP, and states nothing else — no version, no
 * topology, no dependency health. Anything more informative than liveness is a registry operation
 * behind authentication.
 */
@Controller
class HealthController {

    @GetMapping("/healthz")
    ResponseEntity<String> healthz() {
        return ResponseEntity.ok("ok");
    }
}
