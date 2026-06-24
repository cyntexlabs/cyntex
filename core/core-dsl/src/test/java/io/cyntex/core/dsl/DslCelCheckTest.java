package io.cyntex.core.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B4: CEL expression fields are compiled + type-checked at validate time (never evaluated —
 * evaluation is the engine's concern). The §12 ② layer binds the §6 event envelope as its root
 * ({@code op} / {@code ts} / {@code src} / {@code before} / {@code after} / {@code schema}); record
 * field types are unknown offline so {@code after.*} / {@code before.*} resolve to {@code dyn}.
 * A {@code filter} predicate must yield {@code bool}; a {@code map} / {@code push} computed value
 * may yield any type. Violations surface as {@link DslError#ILLEGAL_EXPRESSION} located at the
 * offending field.
 */
class DslCelCheckTest {

    private final DslParser parser = new DslParser();

    @Test
    @DisplayName("a filter referencing an undeclared envelope field is rejected (typo catch)")
    void filterWithUnknownEnvelopeFieldIsRejected() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                transforms:
                  - id: f
                    from: [orders]
                    type: filter
                    expr: "afterr.region == 'US'"
                """;

        Throwable thrown = catchThrowable(() -> parser.parse(pipeline));

        assertThat(thrown).isInstanceOf(DslException.class);
        DslException ex = (DslException) thrown;
        assertThat(ex.code().code()).isEqualTo("dsl.illegal-expression");
        assertThat(ex.path()).isEqualTo("transforms[0].expr");
    }

    @Test
    @DisplayName("a valid envelope predicate compiles clean (no false positive)")
    void validEnvelopePredicateAccepted() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                transforms:
                  - id: f
                    from: [orders]
                    type: filter
                    expr: "after.region == 'US' && op != 'd'"
                """;

        assertThatCode(() -> parser.parse(pipeline)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a filter using the has() presence macro compiles (standard CEL macros enabled)")
    void filterUsingStandardMacroAccepted() {
        // has() is the canonical field-presence test — the most idiomatic predicate over the
        // dyn-typed after.* envelope fields, whose presence is unknown offline (§12).
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                transforms:
                  - id: f
                    from: [orders]
                    type: filter
                    expr: "has(after.region) && after.region != 'US'"
                """;

        assertThatCode(() -> parser.parse(pipeline)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a filter whose result is not boolean is rejected (predicate, not value)")
    void filterWithNonBooleanResultIsRejected() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                transforms:
                  - id: f
                    from: [orders]
                    type: filter
                    expr: "op"
                """;

        Throwable thrown = catchThrowable(() -> parser.parse(pipeline));

        assertThat(thrown).isInstanceOf(DslException.class);
        assertThat(((DslException) thrown).code().code()).isEqualTo("dsl.illegal-expression");
        assertThat(((DslException) thrown).path()).isEqualTo("transforms[0].expr");
    }

    @Test
    @DisplayName("a map computed value that fails to compile is rejected at its field path")
    void mapComputedExpressionWithSyntaxErrorIsRejected() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                transforms:
                  - id: m
                    from: [orders]
                    type: map
                    fields: { tag: "=after.region +" }
                """;

        Throwable thrown = catchThrowable(() -> parser.parse(pipeline));

        assertThat(thrown).isInstanceOf(DslException.class);
        assertThat(((DslException) thrown).code().code()).isEqualTo("dsl.illegal-expression");
        assertThat(((DslException) thrown).path()).isEqualTo("transforms[0].fields.tag");
    }

    @Test
    @DisplayName("a map computed value may yield any type (not constrained to bool)")
    void mapComputedValueAcceptsNonBoolean() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                transforms:
                  - id: m
                    from: [orders]
                    type: map
                    fields: { ingested_at: "=now()", region: $after_region }
                """;

        assertThatCode(() -> parser.parse(pipeline)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a push whole-payload format expression that fails to compile is rejected")
    void pushFormatExpressionWithSyntaxErrorIsRejected() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                serve:
                  from: src_a
                  push:
                    - source: tgt_k
                      format: "=after.region +"
                """;

        Throwable thrown = catchThrowable(() -> parser.parse(pipeline));

        assertThat(thrown).isInstanceOf(DslException.class);
        assertThat(((DslException) thrown).code().code()).isEqualTo("dsl.illegal-expression");
        assertThat(((DslException) thrown).path()).isEqualTo("serve.push[0].format");
    }

    @Test
    @DisplayName("a push object-form format computed field that fails to compile is rejected at its field path")
    void pushObjectFormatComputedExpressionWithSyntaxErrorIsRejected() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                serve:
                  from: src_a
                  push:
                    - source: tgt_k
                      format: { tag: "=after.region +" }
                """;

        Throwable thrown = catchThrowable(() -> parser.parse(pipeline));

        assertThat(thrown).isInstanceOf(DslException.class);
        assertThat(((DslException) thrown).code().code()).isEqualTo("dsl.illegal-expression");
        assertThat(((DslException) thrown).path()).isEqualTo("serve.push[0].format.tag");
    }

    @Test
    @DisplayName("a push object-form format with well-formed computed fields compiles clean")
    void pushObjectFormatWithValidComputedFieldsAccepted() {
        String pipeline = """
                version: cyntex/v1
                kind: pipeline
                id: p
                source: src_a
                serve:
                  from: src_a
                  push:
                    - source: tgt_k
                      format: { region: $after_region, emitted_at: "=now()" }
                """;

        assertThatCode(() -> parser.parse(pipeline)).doesNotThrowAnyException();
    }
}
