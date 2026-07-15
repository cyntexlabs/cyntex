package io.cyntex.cli;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code cli} domain's error codes (ADR-0024 D1) — surface-layer diagnosables that are not DSL
 * semantics: the scaffolding wizard's refusals and bad-input conditions. Thrown as a base
 * {@link io.cyntex.core.common.CyntexException} (no DSL source position) and rendered through the
 * message catalog like any other coded diagnostic.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and the build-time placeholder gate checks the catalog templates against it.
 */
enum CliError implements CyntexErrorCode {

    /** The scaffold target file already exists and {@code --force} was not given. */
    ARTIFACT_EXISTS("cli.artifact-exists", Set.of("path")),

    /** A connector id supplied to the wizard that is not in the bundled catalog. */
    UNKNOWN_CONNECTOR("cli.unknown-connector", Set.of("connector")),

    /** A workspace artifact sits in a directory whose name does not match its declared kind. */
    KIND_DIR_MISMATCH("cli.kind-dir-mismatch", Set.of("path", "kind", "dir")),

    /** A describe / browse verb was given an id that resolves to no resource in the workspace. */
    RESOURCE_NOT_FOUND("cli.resource-not-found", Set.of("id")),

    /** No server among the connect seeds answered the reachability probe. */
    CONNECT_FAILED("cli.connect-failed", Set.of("seeds")),

    /** A verb that needs a live connection was run before the session connected; {@code verb} names it. */
    NOT_CONNECTED("cli.not-connected", Set.of("verb")),

    /** A connected online verb was run before the session authenticated; {@code verb} names it. */
    NOT_AUTHENTICATED("cli.not-authenticated", Set.of("verb")),

    /** A verb whose name is declared and reserved but which is not built yet; {@code verb} names it. */
    VERB_NOT_IMPLEMENTED("cli.verb-not-implemented", Set.of("verb"));

    private final String code;
    private final Set<String> placeholders;

    CliError(String code, Set<String> placeholders) {
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
}
