package io.cyntex.tools.catalog.assembler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog product is byte-locked, so its serializer must be deterministic and stable. This pins
 * the layout: fully expanded (one element per line), two-space indent, empty collections inline, a
 * trailing newline — the same machine-JSON style the schema artifact uses.
 */
class JsonWriterTest {

    private final JsonWriter writer = new JsonWriter();

    @Test
    void serializesANestedTreeFullyExpandedWithTwoSpaceIndent() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("id", "mysql");
        tree.put("pushOut", false);
        tree.put("icon", null);
        tree.put("modes", List.of("cdc", "snapshot"));
        tree.put("config", new ArrayList<>());
        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("capable", true);
        tree.put("sink", sink);

        assertThat(writer.write(tree)).isEqualTo("""
                {
                  "id": "mysql",
                  "pushOut": false,
                  "icon": null,
                  "modes": [
                    "cdc",
                    "snapshot"
                  ],
                  "config": [],
                  "sink": {
                    "capable": true
                  }
                }
                """);
    }

    @Test
    void emptyObjectIsInline() {
        assertThat(writer.write(new LinkedHashMap<>())).isEqualTo("{}\n");
    }

    @Test
    void escapesStringSpecialCharacters() {
        Map<String, Object> tree = new LinkedHashMap<>();
        // quote, backslash, newline, tab, then a low control char (0x01) for the \\uXXXX branch
        tree.put("label", "a\"b\\c\nd\te" + (char) 1);

        assertThat(writer.write(tree)).isEqualTo("""
                {
                  "label": "a\\"b\\\\c\\nd\\te\\u0001"
                }
                """);
    }

    @Test
    void emitsNumbersRaw() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("port", 3306L);
        tree.put("ratio", 1.5);

        assertThat(writer.write(tree)).isEqualTo("""
                {
                  "port": 3306,
                  "ratio": 1.5
                }
                """);
    }
}
