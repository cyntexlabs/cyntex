package io.cyntex.core.dsl;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code dsl} domain's error codes (ADR-0024 D1; the domain's first consumer, plan poc1 B3-7).
 * Each semantic constant maps 1:1 to a corpus rule-vocabulary key (corpus/README.md) — the symbol is
 * the vocabulary key, the canonical code prefixes it with the {@code dsl.} domain. The one exception
 * is {@link #MALFORMED_YAML}: a pure syntax error cannot be a well-formed corpus artifact, so it has
 * no corpus witness and is proven by a direct parser test instead.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and (once the catalog lands in the presentation layer) the build-time placeholder
 * gate checks message templates against it (ADR-0024 D5-4). {@code path} is present on every semantic
 * code — the field path of the offending node, carried both as a typed accessor on {@link DslException}
 * and as a message argument — but absent on {@link #MALFORMED_YAML}, which is pre-semantic.
 */
public enum DslError implements CyntexErrorCode {

    /** A field outside the cyntex/v1 schema (§11.5, strict rejection). */
    UNKNOWN_FIELD("dsl.unknown-field", Set.of("field", "path")),
    /** A field known to the schema but banned in this position (X18/X19). */
    FORBIDDEN_FIELD("dsl.forbidden-field", Set.of("field", "path")),
    /** An id / table / step reference with no target in the batch (§1/§4/§8). */
    MISSING_REFERENCE("dsl.missing-reference", Set.of("ref", "path")),
    /** A bare table name colliding across declared sources (§4). */
    AMBIGUOUS_REFERENCE("dsl.ambiguous-reference", Set.of("ref", "path")),
    /** An option / block illegal for the source mode or boundedness (§4/X7/X10). */
    MODE_MISMATCH("dsl.mode-mismatch", Set.of("field", "mode", "path")),
    /** An enum or format constraint violation (§2/§8). */
    ILLEGAL_VALUE("dsl.illegal-value", Set.of("value", "expected", "path")),
    /** A CEL expression field that fails to compile or type-check (§12); {@code detail} carries
     *  the compiler diagnostic. */
    ILLEGAL_EXPRESSION("dsl.illegal-expression", Set.of("expr", "detail", "path")),
    /** A structural composition rule broken (X17); {@code detail} names the specific rule. */
    COMPOSITION("dsl.composition", Set.of("detail", "path")),
    /** An id collision: top-level / pipeline-internal uniqueness, or step-id shadowing (§2/F8, §5). */
    DUPLICATE_ID("dsl.duplicate-id", Set.of("id", "path")),
    /** A source mode outside the connector's declared capability matrix (§4 / C3); {@code allowed}
     *  lists the connector's legal modes. */
    UNSUPPORTED_MODE("dsl.unsupported-mode", Set.of("connector", "mode", "allowed", "path")),
    /** A connector config value whose type does not match the connector's declared field type
     *  (C3); {@code expected} names the declared type. */
    CONFIG_TYPE_MISMATCH("dsl.config-type-mismatch", Set.of("connector", "field", "expected", "path")),
    /** A connector config value outside the connector's declared enum choices (C3); {@code allowed}
     *  lists the legal values. */
    INVALID_CONFIG_VALUE("dsl.invalid-config-value", Set.of("connector", "field", "value", "allowed", "path")),
    /** A document whose YAML does not parse at all. Pre-semantic, so unlike every other
     *  code it carries no field {@code path} (none is known) and has no corpus witness (a syntax error
     *  cannot be a well-formed corpus artifact) — {@code detail} carries the parser diagnostic, the
     *  typed line / column carry the location, and it is proven by a direct parser test. */
    MALFORMED_YAML("dsl.malformed-yaml", Set.of("detail"));

    private final String code;
    private final Set<String> placeholders;

    DslError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }

    /** The corpus rule-vocabulary key (the canonical code without the {@code dsl.} domain prefix). */
    public String symbol() {
        return code.substring(code.indexOf('.') + 1);
    }

    /** Resolves a corpus-vocabulary symbol (e.g. {@code "unknown-field"}) to its code; throws if unknown. */
    public static DslError ofSymbol(String symbol) {
        for (DslError e : values()) {
            if (e.symbol().equals(symbol)) {
                return e;
            }
        }
        throw new IllegalArgumentException("no DslError for corpus rule '" + symbol + "'");
    }
}
