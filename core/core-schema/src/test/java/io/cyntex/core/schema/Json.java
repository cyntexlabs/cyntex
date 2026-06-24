package io.cyntex.core.schema;

import java.util.List;

/**
 * Minimal JSON document tree for the schema generator. Only the node kinds the generated
 * schema actually uses exist here; this is a build-time tool, not a general JSON library.
 */
sealed interface Json {

    record Str(String value) implements Json {
    }

    record Bool(boolean value) implements Json {
    }

    /** A numeric literal, carried as its verbatim JSON text (e.g. {@code "1000"}). */
    record Num(String raw) implements Json {
    }

    record Arr(List<Json> items) implements Json {
    }

    record Obj(List<Entry> entries) implements Json {
        /** Value for {@code key}, or null if absent. Navigation helper for tests. */
        Json get(String key) {
            for (Entry e : entries) {
                if (e.key().equals(key)) {
                    return e.value();
                }
            }
            return null;
        }
    }

    record Entry(String key, Json value) {
    }

    /** A {@code $ref} pointer into {@code #/$defs}. */
    static Json.Obj ref(String def) {
        return new Json.Obj(List.of(new Json.Entry("$ref", new Json.Str("#/$defs/" + def))));
    }
}
