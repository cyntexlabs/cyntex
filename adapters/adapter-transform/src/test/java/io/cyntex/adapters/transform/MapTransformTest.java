package io.cyntex.adapters.transform;

import static org.assertj.core.api.Assertions.assertThat;

import io.cyntex.core.event.Envelope;
import io.cyntex.core.event.Op;
import io.cyntex.core.model.FieldRule;
import io.cyntex.core.model.TransformBody;
import io.cyntex.spi.transform.TransformPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code map} port: a field projection over the row image. Each declared output is a rule —
 * rename a source field ({@code $src}), drop it ({@code false}), set a literal, or compute a CEL
 * value; unlisted source fields pass through. Declared fields come first in declared order, then the
 * passed-through fields. Events with no row image ({@code ddl}, {@code delete}) bypass untouched.
 */
class MapTransformTest {

    private static TransformPort map(LinkedHashMap<String, FieldRule> fields) {
        return StatelessTransforms.map(MapSpec.from(new TransformBody.MapProjection(fields)));
    }

    private static LinkedHashMap<String, FieldRule> fields(Object... pairs) {
        LinkedHashMap<String, FieldRule> f = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            f.put((String) pairs[i], (FieldRule) pairs[i + 1]);
        }
        return f;
    }

    private static Map<String, Object> afterOf(Envelope out) {
        return out.after();
    }

    @Test
    @DisplayName("renames a source field and consumes it from the passthrough")
    void renamesAndConsumesSource() {
        TransformPort map = map(fields("full_name", FieldRule.rename("name")));
        Envelope row = Envelope.insert(1L, "t",
                new LinkedHashMap<>(Map.of("name", "ada", "age", 30)), null);

        Map<String, Object> after = afterOf(map.transform(row).get(0));

        assertThat(after).containsEntry("full_name", "ada").containsEntry("age", 30);
        assertThat(after).doesNotContainKey("name");
    }

    @Test
    @DisplayName("drops a field named false")
    void dropsField() {
        TransformPort map = map(fields("secret", FieldRule.drop()));
        Envelope row = Envelope.insert(1L, "t",
                new LinkedHashMap<>(Map.of("id", 1, "secret", "x")), null);

        Map<String, Object> after = afterOf(map.transform(row).get(0));

        assertThat(after).containsEntry("id", 1).doesNotContainKey("secret");
    }

    @Test
    @DisplayName("sets a literal value")
    void setsLiteral() {
        TransformPort map = map(fields("stage", FieldRule.literal("prod")));
        Envelope row = Envelope.insert(1L, "t", new LinkedHashMap<>(Map.of("id", 1)), null);

        Map<String, Object> after = afterOf(map.transform(row).get(0));

        assertThat(after).containsEntry("stage", "prod").containsEntry("id", 1);
    }

    @Test
    @DisplayName("computes a CEL value from the row")
    void computesCelValue() {
        TransformPort map = map(fields("greeting", FieldRule.computed("'hi ' + after.name")));
        Envelope row = Envelope.insert(1L, "t", new LinkedHashMap<>(Map.of("name", "ada")), null);

        Map<String, Object> after = afterOf(map.transform(row).get(0));

        assertThat(after).containsEntry("greeting", "hi ada");
    }

    @Test
    @DisplayName("passes unlisted fields through")
    void passesUnlistedThrough() {
        TransformPort map = map(fields("stage", FieldRule.literal("prod")));
        LinkedHashMap<String, Object> src = new LinkedHashMap<>();
        src.put("id", 1);
        src.put("region", "eu");
        Envelope row = Envelope.insert(1L, "t", src, null);

        Map<String, Object> after = afterOf(map.transform(row).get(0));

        assertThat(after).containsEntry("id", 1).containsEntry("region", "eu");
    }

    @Test
    @DisplayName("outputs declared fields first in declared order, then the passthrough")
    void declaredOrderThenPassthrough() {
        LinkedHashMap<String, FieldRule> f = fields(
                "b", FieldRule.literal(2),
                "a", FieldRule.literal(1));
        LinkedHashMap<String, Object> src = new LinkedHashMap<>();
        src.put("z", 26);
        Envelope row = Envelope.insert(1L, "t", src, null);

        Map<String, Object> after = afterOf(map(f).transform(row).get(0));

        assertThat(after.keySet()).containsExactly("b", "a", "z");
    }

    @Test
    @DisplayName("a rule referencing a missing source field is a no-op passthrough, not an error")
    void ruleMissIsNoop() {
        TransformPort map = map(fields("full_name", FieldRule.rename("missing")));
        Envelope row = Envelope.insert(1L, "t", new LinkedHashMap<>(Map.of("id", 1)), null);

        Map<String, Object> after = afterOf(map.transform(row).get(0));

        assertThat(after).containsEntry("id", 1).doesNotContainKey("full_name");
    }

    @Test
    @DisplayName("a rename whose source is missing leaves a same-named existing field untouched")
    void renameMissPreservesSameNamedField() {
        // The output name collides with an existing source field, but the rename source is absent:
        // the rule is a no-op, so the existing field must pass through, not be dropped.
        TransformPort map = map(fields("id", FieldRule.rename("external_id")));
        LinkedHashMap<String, Object> src = new LinkedHashMap<>();
        src.put("id", 5);
        src.put("name", "x");
        Envelope row = Envelope.insert(1L, "t", src, null);

        Map<String, Object> after = afterOf(map.transform(row).get(0));

        assertThat(after).containsEntry("id", 5).containsEntry("name", "x");
    }

    @Test
    @DisplayName("bypasses a ddl event (no row image to project)")
    void bypassesDdl() {
        TransformPort map = map(fields("stage", FieldRule.literal("prod")));
        Envelope ddl = Envelope.ddl(1L, "t", Map.of("kind", "add-column"));

        assertThat(map.transform(ddl)).containsExactly(ddl);
    }

    @Test
    @DisplayName("passes a delete through (before only, no after to project)")
    void passesDeleteThrough() {
        TransformPort map = map(fields("stage", FieldRule.literal("prod")));
        Envelope delete = Envelope.delete(1L, "t", Map.of("id", 1), null);

        assertThat(map.transform(delete)).containsExactly(delete);
    }

    @Test
    @DisplayName("preserves op, ts, src, before and schema; replaces after")
    void preservesEnvelopeSpine() {
        TransformPort map = map(fields("stage", FieldRule.literal("prod")));
        Envelope update = Envelope.update(7L, "orders",
                new LinkedHashMap<>(Map.of("id", 1)),
                new LinkedHashMap<>(Map.of("id", 1)),
                Map.of("v", 2));

        Envelope out = map.transform(update).get(0);

        assertThat(out.op()).isEqualTo(Op.UPDATE);
        assertThat(out.ts()).isEqualTo(7L);
        assertThat(out.src()).isEqualTo("orders");
        assertThat(out.before()).containsEntry("id", 1);
        assertThat(out.schema()).containsEntry("v", 2);
        assertThat(out.after()).containsEntry("stage", "prod");
    }
}
