package io.cyntex.adapters.transform;

import io.cyntex.core.event.Envelope;
import io.cyntex.spi.transform.TransformPort;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code map} port: a field projection over the row image. Declared rules run first in declared
 * order (rename / drop / literal / computed), then every unlisted source field that a rename did not
 * consume passes through in its original order. A rule whose source field is absent is a no-op — the
 * output is simply not produced, never an error. An event with no row image ({@code ddl} or a
 * {@code delete}, which carries only {@code before}) has nothing to project and passes through
 * untouched.
 */
final class MapPort implements TransformPort {

    private final MapSpec spec;
    // Computed rules are compiled once, member-side, keyed by their (unique) output name.
    private final Map<String, RowExpressionProgram> computed;
    // Output names govern their key: an unlisted source of the same name is not also passed through.
    private final Set<String> declaredNames;
    // Source fields a rename takes: consumed, so they are not passed through under their old name.
    private final Set<String> consumedSources;

    MapPort(MapSpec spec) {
        this.spec = spec;
        Map<String, RowExpressionProgram> compiled = new HashMap<>();
        Set<String> declared = new HashSet<>();
        Set<String> consumed = new HashSet<>();
        for (MapRule rule : spec.rules()) {
            declared.add(rule.output());
            if (rule instanceof MapRule.Computed c) {
                compiled.put(c.output(), RowExpressionProgram.value(c.expr()));
            } else if (rule instanceof MapRule.Rename r) {
                consumed.add(r.source());
            }
        }
        this.computed = compiled;
        this.declaredNames = declared;
        this.consumedSources = consumed;
    }

    @Override
    public List<Envelope> transform(Envelope event) {
        Map<String, Object> after = event.after();
        if (after == null) {
            return List.of(event);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (MapRule rule : spec.rules()) {
            switch (rule) {
                case MapRule.Rename r -> {
                    if (after.containsKey(r.source())) {
                        out.put(r.output(), after.get(r.source()));
                    }
                }
                case MapRule.Drop ignored -> {
                    // the field is not carried into the projection
                }
                case MapRule.Literal l -> out.put(l.output(), l.value());
                case MapRule.Computed c -> out.put(c.output(), computed.get(c.output()).eval(event));
            }
        }
        after.forEach((field, value) -> {
            if (!declaredNames.contains(field) && !consumedSources.contains(field) && !out.containsKey(field)) {
                out.put(field, value);
            }
        });
        return List.of(new Envelope(event.op(), event.ts(), event.src(), event.before(), out, event.schema()));
    }
}
