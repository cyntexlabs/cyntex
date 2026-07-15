package io.cyntex.e2e;

import java.util.Locale;

/**
 * The words that introduce a matcher.
 *
 * <p>An enum rather than a set of string cases, so the parser's dispatch is an exhaustive switch: a
 * word added here stops the parser and the schema generator from compiling until both say what it
 * means. That is the only version of "the vocabulary is single-sourced" a reader can trust, since it
 * is checked by the compiler rather than by whoever remembers.
 *
 * <p>A word is admitted only once something real answers it. {@code count} reads the target
 * database and {@code state} reads the published observation; a word whose source the runtime does
 * not populate would poll an empty reading until timeout, which is a worse answer than not offering
 * the word.
 */
enum MatcherWord {

    /** Rows present at an endpoint, read from the endpoint itself. */
    COUNT,

    /** The pipeline's published lifecycle state. */
    STATE;

    String word() {
        return name().toLowerCase(Locale.ROOT);
    }
}
