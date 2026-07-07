package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationTest {

    @Test
    void rejectsBlankId() {
        assertThatThrownBy(() -> new Operation("  ", Scope.READ, false, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonDotScopedId() {
        assertThatThrownBy(() -> new Operation("apply", Scope.WRITE, true, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("apply");
    }

    @Test
    void exposureIsDefensivelyCopiedAndUnmodifiable() {
        Map<Frontend, Maturity> source = new HashMap<>();
        source.put(Frontend.CLI, Maturity.POC);
        Operation op = new Operation("artifact.apply", Scope.WRITE, true, null, source);

        source.put(Frontend.MCP, Maturity.BETA); // mutating the source must not leak into the operation

        assertThat(op.exposure()).containsOnlyKeys(Frontend.CLI);
        assertThatThrownBy(() -> op.exposure().put(Frontend.REST, Maturity.GA))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullExposureMaturity() {
        Map<Frontend, Maturity> bad = new HashMap<>();
        bad.put(Frontend.CLI, null);
        assertThatThrownBy(() -> new Operation("artifact.apply", Scope.WRITE, true, null, bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carriesScopeAuditFlagAndSchemaRef() {
        SchemaRef schema = new SchemaRef("#/$defs/ApplyRequest", "#/$defs/ApplyResult");
        Operation op =
                new Operation("artifact.apply", Scope.WRITE, true, schema, Map.of(Frontend.CLI, Maturity.POC));

        assertThat(op.scope()).isEqualTo(Scope.WRITE);
        assertThat(op.audited()).isTrue();
        assertThat(op.schema()).isEqualTo(schema);
        assertThat(op.schema().params()).isEqualTo("#/$defs/ApplyRequest");
    }
}
