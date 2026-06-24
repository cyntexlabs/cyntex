package io.cyntex.core.dsl;

import io.cyntex.core.common.Severity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DslErrorTest {

    @Test
    void everyCodeIsInTheDslDomainAndErrorSeverity() {
        for (DslError e : DslError.values()) {
            assertThat(e.code()).startsWith("dsl.");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
        }
    }

    @Test
    void carriesTheCorpusVocabularyCodes() {
        assertThat(DslError.values()).extracting(DslError::code).containsExactlyInAnyOrder(
                "dsl.unknown-field",
                "dsl.forbidden-field",
                "dsl.missing-reference",
                "dsl.ambiguous-reference",
                "dsl.mode-mismatch",
                "dsl.illegal-value",
                "dsl.illegal-expression",
                "dsl.composition",
                "dsl.duplicate-id",
                "dsl.unsupported-mode",
                "dsl.config-type-mismatch",
                "dsl.invalid-config-value",
                // pre-semantic syntax error — no corpus witness (a syntax error cannot be well-formed)
                "dsl.malformed-yaml");
    }

    @Test
    void declaresThePlaceholderContractPerCode() {
        assertThat(DslError.UNKNOWN_FIELD.placeholders()).containsExactlyInAnyOrder("field", "path");
        assertThat(DslError.FORBIDDEN_FIELD.placeholders()).containsExactlyInAnyOrder("field", "path");
        assertThat(DslError.MISSING_REFERENCE.placeholders()).containsExactlyInAnyOrder("ref", "path");
        assertThat(DslError.AMBIGUOUS_REFERENCE.placeholders()).containsExactlyInAnyOrder("ref", "path");
        assertThat(DslError.MODE_MISMATCH.placeholders()).containsExactlyInAnyOrder("field", "mode", "path");
        assertThat(DslError.ILLEGAL_VALUE.placeholders()).containsExactlyInAnyOrder("value", "expected", "path");
        assertThat(DslError.ILLEGAL_EXPRESSION.placeholders()).containsExactlyInAnyOrder("expr", "detail", "path");
        assertThat(DslError.COMPOSITION.placeholders()).containsExactlyInAnyOrder("detail", "path");
        assertThat(DslError.DUPLICATE_ID.placeholders()).containsExactlyInAnyOrder("id", "path");
        assertThat(DslError.UNSUPPORTED_MODE.placeholders())
                .containsExactlyInAnyOrder("connector", "mode", "allowed", "path");
        assertThat(DslError.CONFIG_TYPE_MISMATCH.placeholders())
                .containsExactlyInAnyOrder("connector", "field", "expected", "path");
        assertThat(DslError.INVALID_CONFIG_VALUE.placeholders())
                .containsExactlyInAnyOrder("connector", "field", "value", "allowed", "path");
        // malformed-yaml is pre-semantic: it carries only the parser detail, no field path
        assertThat(DslError.MALFORMED_YAML.placeholders()).containsExactlyInAnyOrder("detail");
    }

    @Test
    void resolvesByCorpusVocabularySymbol() {
        assertThat(DslError.ofSymbol("unknown-field")).isSameAs(DslError.UNKNOWN_FIELD);
        assertThat(DslError.ofSymbol("mode-mismatch")).isSameAs(DslError.MODE_MISMATCH);
    }

    @Test
    void symbolLookupRejectsUnknownVocabulary() {
        assertThatThrownBy(() -> DslError.ofSymbol("no-such-rule"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
