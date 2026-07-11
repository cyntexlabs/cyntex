package io.cyntex.adapters.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.core.event.Envelope;
import io.cyntex.spi.transform.TransformPort;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code filter} port: a CEL predicate over the event envelope keeps or drops each row. Only row
 * events are judged; a non-row {@code ddl} event bypasses the predicate untouched, since swallowing
 * a schema change would silently break the schema-evolution chain downstream.
 */
class FilterTransformTest {

    @Test
    @DisplayName("keeps a row whose predicate is true")
    void keepsRowWhenPredicateTrue() {
        TransformPort filter = StatelessTransforms.filter("after.deleted == 0");
        Envelope row = Envelope.insert(1L, "orders", Map.of("id", 1, "deleted", 0), null);

        assertThat(filter.transform(row)).containsExactly(row);
    }

    @Test
    @DisplayName("drops a row whose predicate is false")
    void dropsRowWhenPredicateFalse() {
        TransformPort filter = StatelessTransforms.filter("after.deleted == 0");
        Envelope row = Envelope.insert(1L, "orders", Map.of("id", 1, "deleted", 1), null);

        assertThat(filter.transform(row)).isEmpty();
    }

    @Test
    @DisplayName("binds op as its wire symbol so a predicate can select by change kind")
    void bindsOpAsWireSymbol() {
        TransformPort insertsOnly = StatelessTransforms.filter("op == 'i'");
        Envelope insert = Envelope.insert(1L, "t", Map.of("id", 1), null);
        Envelope update = Envelope.update(1L, "t", Map.of("id", 1), Map.of("id", 2), null);

        assertThat(insertsOnly.transform(insert)).containsExactly(insert);
        assertThat(insertsOnly.transform(update)).isEmpty();
    }

    @Test
    @DisplayName("evaluates a delete like any other row event (before is bound)")
    void evaluatesDeleteAsRow() {
        TransformPort dropDeletes = StatelessTransforms.filter("op != 'd'");
        Envelope delete = Envelope.delete(1L, "t", Map.of("id", 1), null);
        Envelope insert = Envelope.insert(1L, "t", Map.of("id", 1), null);

        assertThat(dropDeletes.transform(delete)).isEmpty();
        assertThat(dropDeletes.transform(insert)).containsExactly(insert);
    }

    @Test
    @DisplayName("bypasses a ddl event without evaluating the predicate")
    void bypassesDdlEvent() {
        TransformPort filter = StatelessTransforms.filter("after.deleted == 0");
        Envelope ddl = Envelope.ddl(1L, "orders", Map.of("kind", "add-column"));

        assertThat(filter.transform(ddl)).containsExactly(ddl);
    }

    @Test
    @DisplayName("a row expression that fails to evaluate surfaces a coded transform.expression-failed")
    void codesRowExpressionEvaluationFailure() {
        // now() type-checks (it is declared in the compile environment) but is unbound in the runtime,
        // so the predicate compiles and then fails at eval — the operator must see a coded diagnostic
        // naming the expression, not a bare crash.
        TransformPort filter = StatelessTransforms.filter("now() == now()");
        Envelope row = Envelope.insert(1L, "orders", Map.of("id", 1), null);

        assertThatThrownBy(() -> filter.transform(row))
                .isInstanceOf(CyntexException.class)
                .satisfies(thrown -> {
                    CyntexException e = (CyntexException) thrown;
                    assertThat(e.code().code()).isEqualTo("transform.expression-failed");
                    assertThat(e.args().get("expr")).isEqualTo("now() == now()");
                    assertThat(e.args()).containsKey("detail");
                });
    }
}
