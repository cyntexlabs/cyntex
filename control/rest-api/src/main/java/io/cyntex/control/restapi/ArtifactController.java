package io.cyntex.control.restapi;

import io.cyntex.control.core.ApplyResult;
import io.cyntex.control.core.ApplyService;
import io.cyntex.control.core.ArtifactDraft;
import io.cyntex.control.core.ArtifactQueryService;
import io.cyntex.control.core.StoredArtifact;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The artifact verbs projected onto HTTP: apply (write), get and list (read). Each handler is a thin
 * pass-through to a control-core service — it decodes the request, calls the verb, and encodes the
 * result — and carries no business logic of its own. Every handler is annotated with the operation id
 * it projects ({@link Verb}); mounted under the {@code /api} prefix by the path configuration.
 */
@RestController
class ArtifactController {

    private final ApplyService applyService;
    private final ArtifactQueryService queryService;

    ArtifactController(ApplyService applyService, ArtifactQueryService queryService) {
        this.applyService = applyService;
        this.queryService = queryService;
    }

    @Verb("artifact.apply")
    @PostMapping("/artifacts:apply")
    ApplyResult apply(@RequestBody ApplyRequest request) {
        // Refuse a body with no drafts array at the boundary as a coded 400, rather than letting a null trip
        // the service's bare invariant guard (a 500). A missing body is already a framework-level 400 upstream.
        List<ArtifactDraft> drafts = MalformedRequest.require(
                request == null ? null : request.drafts(), "the request must carry a `drafts` array");
        return applyService.apply(drafts);
    }

    @Verb("artifact.get")
    @GetMapping("/artifacts/{id}")
    ResponseEntity<StoredArtifact> get(@PathVariable("id") String id) {
        // ResponseEntity.of maps a present artifact to 200 and an absent one to 404, with no error logic here.
        return ResponseEntity.of(queryService.get(id));
    }

    @Verb("artifact.list")
    @GetMapping("/artifacts")
    ArtifactList list(@RequestParam(name = "kind", required = false) String kind) {
        return new ArtifactList(queryService.list(kind));
    }
}
