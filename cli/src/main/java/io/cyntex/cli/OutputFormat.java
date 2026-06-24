package io.cyntex.cli;

import java.util.Iterator;
import java.util.List;

/**
 * The {@code -o} / {@code --output} rendering choice shared by the offline verbs. {@code TEXT} is the
 * human surface (colour-aware, diagnostics to stderr); {@code JSON} and {@code YAML} are the machine
 * surfaces — a stable structured envelope on stdout for scripts and AI to consume. Parsing is
 * case-insensitive; the documented spelling, and what completion offers, is the lower-case form.
 */
enum OutputFormat {
    TEXT,
    JSON,
    YAML;

    /**
     * Completion / help candidates for the {@code -o} option: the lower-case documented spelling
     * rather than the upper-case enum constant names picocli would list by default.
     */
    static final class Candidates implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return List.of("text", "json", "yaml").iterator();
        }
    }
}
