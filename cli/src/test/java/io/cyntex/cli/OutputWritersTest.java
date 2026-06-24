package io.cyntex.cli;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The zero-dependency structured-output writers for {@code -o json|yaml}. They serialize an ordered
 * tree of {@code Map} / {@code List} / scalars deterministically; the surface ring carries no JSON
 * or YAML library (rule R6), so these are hand-written, like their core-ring counterparts.
 */
class OutputWritersTest {

    /** A string carrying a NUL (0x00) and a form-feed (0x0c) — built from code points, no escapes. */
    private static final String WITH_CONTROLS = "a" + (char) 0 + "b" + (char) 12 + "c";

    @Test
    void jsonWritesAnOrderedObjectWithAnEmptyArray() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "valid");
        m.put("resourceCount", 3);
        m.put("diagnostics", List.of());
        assertThat(JsonOut.write(m)).isEqualTo(
                "{\n  \"status\": \"valid\",\n  \"resourceCount\": 3,\n  \"diagnostics\": []\n}");
    }

    @Test
    void jsonNestsArraysOfObjects() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", "dsl.x");
        d.put("line", 2);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diagnostics", List.of(d));
        assertThat(JsonOut.write(m)).isEqualTo(
                "{\n  \"diagnostics\": [\n    {\n      \"code\": \"dsl.x\",\n      \"line\": 2\n    }\n  ]\n}");
    }

    @Test
    void jsonEscapesQuotesAndBackslashes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "a\"b\\c");
        assertThat(JsonOut.write(m)).isEqualTo("{\n  \"k\": \"a\\\"b\\\\c\"\n}");
    }

    @Test
    void jsonEscapesControlCharacters() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", WITH_CONTROLS);
        assertThat(JsonOut.write(m)).isEqualTo("{\n  \"k\": \"a\\u0000b\\u000cc\"\n}");
    }

    @Test
    void yamlWritesABlockMappingWithEmptyCollections() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "valid");
        m.put("resourceCount", 3);
        m.put("diagnostics", List.of());
        assertThat(YamlOut.write(m)).isEqualTo("status: valid\nresourceCount: 3\ndiagnostics: []");
    }

    @Test
    void yamlNestsListItemsAsIndentedBlocks() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("code", "dsl.x");
        d.put("line", 2);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diagnostics", List.of(d));
        assertThat(YamlOut.write(m)).isEqualTo("diagnostics:\n  - code: dsl.x\n    line: 2");
    }

    @Test
    void yamlQuotesScalarsThatNeedIt() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "a: b");
        assertThat(YamlOut.write(m)).isEqualTo("k: \"a: b\"");
    }

    @Test
    void yamlEscapesControlCharactersInQuotedScalars() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", WITH_CONTROLS);
        assertThat(YamlOut.write(m)).isEqualTo("k: \"a\\u0000b\\u000cc\"");
    }

    @Test
    void yamlQuotesBoolLikeStringsSoTheyStayStrings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "true");
        assertThat(YamlOut.write(m)).isEqualTo("k: \"true\"");
    }

    @Test
    void yamlQuotesNumericLikeStringsSoTheyStayStrings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", "3.14");
        assertThat(YamlOut.write(m)).isEqualTo("k: \"3.14\"");
    }
}
