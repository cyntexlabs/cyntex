package io.cyntex.adapters.transform;

import io.cyntex.core.event.Envelope;
import io.cyntex.core.event.Op;
import io.cyntex.spi.transform.TransformPort;
import java.util.List;

/**
 * The {@code filter} port: a CEL predicate over the event envelope keeps or drops each row. Only row
 * events are judged; a {@code ddl} event carries no row and bypasses the predicate untouched, since
 * dropping a schema change would silently break the downstream schema-evolution chain.
 */
final class FilterPort implements TransformPort {

    private final RowExpressionProgram predicate;

    FilterPort(String expr) {
        this.predicate = RowExpressionProgram.predicate(expr);
    }

    @Override
    public List<Envelope> transform(Envelope event) {
        if (event.op() == Op.DDL) {
            return List.of(event);
        }
        return Boolean.TRUE.equals(predicate.eval(event)) ? List.of(event) : List.of();
    }
}
