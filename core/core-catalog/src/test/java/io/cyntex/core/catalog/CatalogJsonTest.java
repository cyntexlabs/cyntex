package io.cyntex.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class CatalogJsonTest {

    @Test
    void parsesScalars() {
        assertThat(CatalogJson.parse("\"hello\"")).isEqualTo("hello");
        assertThat(CatalogJson.parse("true")).isEqualTo(Boolean.TRUE);
        assertThat(CatalogJson.parse("false")).isEqualTo(Boolean.FALSE);
        assertThat(CatalogJson.parse("null")).isNull();
    }

    @Test
    void parsesIntegersAsLongAndDecimalsAsDouble() {
        assertThat(CatalogJson.parse("3306")).isEqualTo(3306L);
        assertThat(CatalogJson.parse("-12")).isEqualTo(-12L);
        assertThat(CatalogJson.parse("1.5")).isEqualTo(1.5d);
        assertThat(CatalogJson.parse("-2.5e3")).isEqualTo(-2500.0d);
    }

    @Test
    void parsesIntegersThatOverflowLongAsBigInteger() {
        // Some connector specs carry unsigned-64-bit defaults (e.g. 2^64-1) that exceed Long; the
        // reader must keep them exactly rather than fail or silently lose precision.
        assertThat(CatalogJson.parse("18446744073709551615"))
                .isEqualTo(new java.math.BigInteger("18446744073709551615"));
    }

    @Test
    void parsesStringEscapesAndUnicode() {
        assertThat(CatalogJson.parse("\"a\\\"b\\\\c\\n\"")).isEqualTo("a\"b\\c\n");
        assertThat(CatalogJson.parse("\"\\u20ac\"")).isEqualTo("€"); // euro sign via unicode escape
        assertThat(CatalogJson.parse("\"€\"")).isEqualTo("€"); // literal multibyte utf-8
        assertThat(CatalogJson.parse("\"a\\/b\"")).isEqualTo("a/b");
    }

    @Test
    void parsesEmptyAndNestedContainers() {
        assertThat(CatalogJson.parse("{}")).isEqualTo(Map.of());
        assertThat(CatalogJson.parse("[]")).isEqualTo(List.of());
        assertThat(CatalogJson.parse("[\"a\", \"b\"]")).isEqualTo(List.of("a", "b"));

        Object tree = CatalogJson.parse("{ \"k\": { \"n\": [true, null] } }");
        assertThat(tree).isInstanceOf(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesObjectKeyOrder() {
        Map<String, Object> tree = (Map<String, Object>) CatalogJson.parse("{\"b\":1,\"a\":2,\"c\":3}");
        assertThat(tree.keySet()).containsExactly("b", "a", "c");
    }

    @Test
    void toleratesInsignificantWhitespace() {
        assertThat(CatalogJson.parse("  {  \"a\" :\n \"x\" }  ")).isEqualTo(Map.of("a", "x"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> CatalogJson.parse("{\"a\":}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogJson.parse("[1,2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogJson.parse("{\"a\":1} trailing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPathologicallyDeepNestingWithoutAStackOverflow() {
        String deep = "[".repeat(5000) + "]".repeat(5000);

        assertThatThrownBy(() -> CatalogJson.parse(deep)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesTheSameTreeAsAJsonLibraryForCatalogShapedInput() throws Exception {
        // Catalog product JSON is string/bool/array/object/null only (no bare numbers), so the
        // hand-rolled reader must agree with a real JSON library byte-for-tree.
        String json = """
                {
                  "id": "mysql",
                  "group": "database",
                  "modes": ["cdc", "snapshot"],
                  "sink": {"capable": true, "writeSemantics": ["upsert", "append"]},
                  "icon": null,
                  "config": [
                    {"name": "host", "label": {"en_US": "Host"}, "secret": false}
                  ]
                }
                """;
        Object mine = CatalogJson.parse(json);
        Object reference = new ObjectMapper().readValue(json, Object.class);
        assertThat(mine).isEqualTo(reference);
    }
}
