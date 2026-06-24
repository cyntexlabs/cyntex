package io.cyntex.cli;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;
import io.cyntex.core.dsl.DslError;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error-code message catalog: the presentation-layer renderer that turns a coded exception into
 * a user-facing diagnostic via the bundled {@code messages/en.yml} catalog, substituting named
 * placeholders from the exception's arguments and falling back to the bare canonical code.
 */
class MessageCatalogTest {

    private static final MessageCatalog EN = MessageCatalog.bundled();

    @Test
    void rendersNamedPlaceholdersFromTheArgs() {
        MessageCatalog.Rendered r = EN.render(DslError.UNKNOWN_FIELD,
                Map.of("field", "srcc", "path", "source"));
        assertThat(r.message()).contains("srcc").contains("source").doesNotContain("{");
    }

    @Test
    void carriesASolutionHint() {
        MessageCatalog.Rendered r = EN.render(DslError.UNKNOWN_FIELD,
                Map.of("field", "srcc", "path", "source"));
        assertThat(r.solution()).isNotNull().isNotEmpty();
    }

    @Test
    void unknownCodeFallsBackToTheBareCanonicalCode() {
        CyntexErrorCode absent = new CyntexErrorCode() {
            @Override
            public String code() {
                return "cli.not-in-catalog-test";
            }

            @Override
            public Severity severity() {
                return Severity.ERROR;
            }

            @Override
            public Set<String> placeholders() {
                return Set.of();
            }
        };
        MessageCatalog.Rendered r = EN.render(absent, Map.of());
        assertThat(r.message()).isEqualTo("cli.not-in-catalog-test");
        assertThat(r.solution()).isNull();
    }

    @Test
    void everyDslCodeRendersFullyWithNoLeftoverPlaceholders() {
        for (DslError code : DslError.values()) {
            Map<String, Object> args = new HashMap<>();
            for (String placeholder : code.placeholders()) {
                args.put(placeholder, "X");
            }
            MessageCatalog.Rendered r = EN.render(code, args);
            assertThat(r.message())
                    .as("message for %s must be present in the catalog and fully substituted", code.code())
                    .isNotEqualTo(code.code())
                    .doesNotContain("{")
                    .doesNotContain("}");
        }
    }

    @Test
    void readerKeepsColonsInsideQuotedValues() {
        Map<String, MessageCatalog.Entry> parsed = MessageCatalog.parse("x.y:\n  message: \"a: b {z}\"\n");
        assertThat(parsed.get("x.y").message()).isEqualTo("a: b {z}");
        assertThat(parsed.get("x.y").solution()).isNull();
    }
}
