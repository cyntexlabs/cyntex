package io.cyntex.control.restapi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The connection-test verb projected onto HTTP: the one synchronous control-to-runtime operation the
 * first landing opens. It is reserved and routed here so its projection is registry-derived, but the
 * external probe and the persistence of its result land in later slices — so it answers
 * {@code 501 Not Implemented} rather than fabricating a result.
 */
@RestController
class ConnectionController {

    @Verb("connection.test")
    @PostMapping("/connections:test")
    ResponseEntity<Void> test() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
