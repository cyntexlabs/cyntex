package io.cyntex.control.restapi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The topology verb projected onto HTTP. Cluster membership is sensitive — it must never be readable
 * anonymously — so unlike the liveness probe it is a registered operation, reserved and routed here so
 * its projection is registry-derived. The member listing and the authentication interceptor that must
 * guard it land in later slices, so until then it answers {@code 501 Not Implemented} and exposes
 * nothing.
 */
@RestController
class ClusterController {

    @Verb("cluster.members")
    @GetMapping("/cluster/members")
    ResponseEntity<Void> members() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
