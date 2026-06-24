package io.cyntex.core.model.canonical;

import java.util.List;

/**
 * Intermediate render tree between the resource model and YAML text. The writer encodes
 * key order / omission / normalization while building this tree; the emitter encodes
 * layout and quoting (canonical-form.md §6) while rendering it.
 */
sealed interface Node {

    record MapN(List<Entry> entries) implements Node {
    }

    record Entry(String key, Node value) {
    }

    record SeqN(List<Node> items) implements Node {
    }

    record ScalarN(Object value, Style style) implements Node {
    }

    enum Style {
        /** Plain when safe under the YAML 1.2 core schema, double-quoted otherwise. */
        AUTO,
        /** CEL expression field — always double-quoted (canonical-form.md §6). */
        EXPRESSION,
        /** Multi-line user content (script / sql) — literal block scalar. */
        LITERAL
    }
}
