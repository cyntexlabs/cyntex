package io.cyntex.control.restapi;

import io.cyntex.control.core.PipelineLifecycleService;
import io.cyntex.core.lifecycle.DesiredState;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pipeline lifecycle verbs projected onto HTTP: start / stop / pause / resume, each a custom method on
 * a pipeline instance ({@code POST /api/pipelines/{id}:start}). Each handler is a thin pass-through to the
 * control-core lifecycle service — it names the target pipeline and the authenticated caller, then calls
 * the verb — and carries no business logic of its own: the state-machine check, the revision-compatibility
 * check, and the audited desired-state write all live in the service.
 *
 * <p>The caller principal is read from the request attribute the {@link AuthInterceptor} stashes after it
 * authenticates the credential, so the audited write records the real caller rather than a placeholder.
 * There is deliberately no {@code rewind} verb: a re-dig is the explicit two-step stop then start, composed
 * by the caller.
 */
@RestController
class PipelineController {

    private final PipelineLifecycleService lifecycle;

    PipelineController(PipelineLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Verb("pipeline.start")
    @PostMapping("/pipelines/{id}:start")
    DesiredState start(@PathVariable("id") String id,
                       @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) String principal) {
        return lifecycle.start(principal, id);
    }

    @Verb("pipeline.stop")
    @PostMapping("/pipelines/{id}:stop")
    DesiredState stop(@PathVariable("id") String id,
                      @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) String principal) {
        return lifecycle.stop(principal, id);
    }

    @Verb("pipeline.pause")
    @PostMapping("/pipelines/{id}:pause")
    DesiredState pause(@PathVariable("id") String id,
                       @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) String principal) {
        return lifecycle.pause(principal, id);
    }

    @Verb("pipeline.resume")
    @PostMapping("/pipelines/{id}:resume")
    DesiredState resume(@PathVariable("id") String id,
                        @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) String principal) {
        return lifecycle.resume(principal, id);
    }
}
