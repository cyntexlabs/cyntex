package io.cyntex.core.lifecycle;

import io.cyntex.core.common.Severity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleErrorTest {

    @Test
    void everyCodeIsInTheLifecycleDomainAndErrorSeverity() {
        for (LifecycleError e : LifecycleError.values()) {
            assertThat(e.code()).startsWith("lifecycle.");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
        }
    }

    @Test
    void carriesTheLifecycleVocabularyCodes() {
        assertThat(LifecycleError.values()).extracting(LifecycleError::code).containsExactlyInAnyOrder(
                "lifecycle.illegal-transition",
                // start/resume refused because the pipeline's revision is not the latest applied one
                "lifecycle.incompatible-revision",
                // a lifecycle verb named a pipeline that was never applied
                "lifecycle.unknown-pipeline");
    }

    @Test
    void declaresThePlaceholderContractPerCode() {
        assertThat(LifecycleError.ILLEGAL_TRANSITION.placeholders())
                .containsExactlyInAnyOrder("from", "verb");
        // requested = the revision the start/resume would run at; latest = the latest applied revision
        assertThat(LifecycleError.INCOMPATIBLE_REVISION.placeholders())
                .containsExactlyInAnyOrder("requested", "latest");
        // pipeline = the id the caller named
        assertThat(LifecycleError.UNKNOWN_PIPELINE.placeholders())
                .containsExactlyInAnyOrder("pipeline");
    }
}
