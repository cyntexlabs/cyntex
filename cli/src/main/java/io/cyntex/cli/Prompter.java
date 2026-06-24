package io.cyntex.cli;

import java.util.List;

/**
 * The wizard's input seam: every interactive question goes through this interface, so the question
 * flow is driven by a real JLine line reader in production and by a scripted fake in tests (the
 * symmetric counterpart to {@link CliIo}'s output writers). The wizard never touches a terminal
 * directly.
 */
interface Prompter {

    /** Free-text answer; returns the raw reply (empty string if the user just accepts / skips). The
     *  {@code defaultValue} is shown as a hint — the caller decides what an empty reply means. */
    String ask(String question, String defaultValue);

    /** Masked free-text answer (e.g. a password); returns the raw reply, empty when skipped. */
    String secret(String question);

    /** Pick exactly one of {@code options}; returns the chosen option. */
    String choose(String question, List<String> options);

    /**
     * Capture a multi-line block (e.g. SQL or a JS body); returns the joined lines with no trailing
     * newline (the caller normalizes block-scalar layout). An immediately-finished / skipped block
     * returns the empty string.
     */
    String lines(String question);
}
