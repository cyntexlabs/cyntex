package io.cyntex.control.restapi;

import io.cyntex.control.core.BootstrapService;
import io.cyntex.control.core.CallerOrigin;
import io.cyntex.control.core.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * The pre-authentication entry points, served at the root outside the {@code /api} verb surface — a caller
 * cannot be required to present a credential to obtain one. Like the liveness probe they are a plain
 * {@code @Controller}, so the {@code /api} path prefix does not sweep them up and the {@code /api}
 * authentication interceptor never guards them; each self-guards instead.
 *
 * <ul>
 *   <li>{@code POST /auth/login} — verify a username / password and mint a short-lived session token. A bad
 *       credential is refused with {@code control.auth-failed} (401), revealing nothing about which half was
 *       wrong.
 *   <li>{@code POST /auth/bootstrap} — the zero-user exception: on a brand-new server create the first admin,
 *       accepted only from the loopback interface and only while no user exists. The remote address is
 *       classified here into a {@link CallerOrigin} and passed to the service, which owns the guards.
 * </ul>
 */
@Controller
class AuthController {

    private final LoginService loginService;
    private final BootstrapService bootstrapService;

    AuthController(LoginService loginService, BootstrapService bootstrapService) {
        this.loginService = loginService;
        this.bootstrapService = bootstrapService;
    }

    @PostMapping("/auth/login")
    ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        return ResponseEntity.ok(new LoginResponse(loginService.login(request.username(), request.password())));
    }

    @PostMapping("/auth/bootstrap")
    ResponseEntity<Void> bootstrap(@RequestBody BootstrapRequest request, HttpServletRequest http) {
        CallerOrigin origin = CallerOrigins.classify(http.getRemoteAddr());
        bootstrapService.createFirstAdmin(origin, request.username(), request.password());
        return ResponseEntity.noContent().build();
    }
}
