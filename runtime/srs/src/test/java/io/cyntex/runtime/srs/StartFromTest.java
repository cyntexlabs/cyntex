package io.cyntex.runtime.srs;

import io.cyntex.core.common.CyntexException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing a pipeline's {@code start_from} setting — where this pipeline starts consuming the incremental
 * tail — into a typed entry point. The three forms are the two keywords {@code earliest} / {@code latest}
 * and an ISO-8601 instant; the value is a free string at the authoring layer (the schema does not constrain
 * its format), so an unrecognized value is a runtime, user-facing error rather than a validate-layer one.
 */
class StartFromTest {

    @Test
    void parsesEarliest() {
        assertThat(StartFrom.parse("earliest")).isEqualTo(StartFrom.earliest());
    }

    @Test
    void parsesLatest() {
        assertThat(StartFrom.parse("latest")).isEqualTo(StartFrom.latest());
    }

    @Test
    void parsesAnIso8601Instant() {
        assertThat(StartFrom.parse("2026-07-11T00:00:00Z"))
                .isEqualTo(StartFrom.at(Instant.parse("2026-07-11T00:00:00Z")));
    }

    @Test
    void rejectsAValueThatIsNeitherKeywordNorInstantWithACode() {
        // start_from is a free string at authoring time, so a value like this reaches the runtime; it is a
        // user-facing, diagnosable error carrying the offending value, not a bare crash.
        assertThatThrownBy(() -> StartFrom.parse("yesterday"))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException ce = (CyntexException) e;
                    assertThat(ce.code().code()).isEqualTo("capture.start-from-unparsable");
                    assertThat(ce.args()).containsEntry("value", "yesterday");
                });
    }

    @Test
    void rejectsNullAsAProgrammerErrorNotACode() {
        // A null start_from is an invariant violation (the setting defaults to a value); it stays a bare NPE
        // rather than being laundered into a user-facing code.
        assertThatThrownBy(() -> StartFrom.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
