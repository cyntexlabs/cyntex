package io.cyntex.core.common;

import java.util.Set;

/**
 * The structural contract every first-party error code implements (ADR-0024 D1).
 *
 * <p>Each error code is an {@code enum} constant of an {@code enum implements CyntexErrorCode};
 * the constructor carries this metadata. Enums are chosen over the legacy annotation + runtime
 * reflection scanner because {@code values()} enumerates the whole code set without scanning and
 * GraalVM native-image needs zero reflection configuration for them — the system has zero
 * start-up cost (ADR-0024 D1, the head "never copy" item from the legacy design).
 *
 * <p>Message text lives elsewhere (ADR-0024 D3): the per-locale catalog in the presentation
 * layer, never here. This contract exposes only structural metadata.
 *
 * <p>Global uniqueness of {@link #code()} across enums and modules is <em>not</em> guaranteed by
 * the compiler (it only protects constant names within one enum) — it is a build-time gate in
 * {@code arch-tests} (ADR-0024 D5).
 */
public interface CyntexErrorCode {

    /** Canonical code {@code <domain>.<symbol>} (ADR-0024 D2); the single, stable external identity. */
    String code();

    /** Severity used by exit codes, structured output, and log levels (ADR-0024 D7). */
    Severity severity();

    /**
     * The names of the dynamic placeholders this code's message templates reference
     * (ADR-0024 D7 / D5-4). Throw sites must supply a named argument for each; the build-time
     * placeholder gate (once the catalog lands) checks templates against this contract.
     */
    Set<String> placeholders();
}
