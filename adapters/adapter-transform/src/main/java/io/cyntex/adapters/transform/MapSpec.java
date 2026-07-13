package io.cyntex.adapters.transform;

import io.cyntex.core.model.FieldRule;
import io.cyntex.core.model.TransformBody;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The serializable shape of a {@code map} projection: its rules in declared order. It is the currency
 * the app assembly root captures in the Jet supplier, so it holds no model or engine type — only the
 * self-contained rules a member rebuilds the port from. Build it from the parsed projection with
 * {@link #from}; the port is built from it with {@link StatelessTransforms#map}.
 */
public final class MapSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<MapRule> rules;

    private MapSpec(List<MapRule> rules) {
        this.rules = rules;
    }

    /** Translates a parsed map projection into its serializable rule list, preserving declared order. */
    public static MapSpec from(TransformBody.MapProjection projection) {
        List<MapRule> rules = new ArrayList<>();
        projection.fields().forEach((output, rule) -> rules.add(toRule(output, rule)));
        return new MapSpec(List.copyOf(rules));
    }

    private static MapRule toRule(String output, FieldRule rule) {
        return switch (rule) {
            case FieldRule.Rename r -> new MapRule.Rename(output, r.sourceField());
            case FieldRule.Drop ignored -> new MapRule.Drop(output);
            case FieldRule.Literal l -> new MapRule.Literal(output, l.value());
            case FieldRule.Computed c -> new MapRule.Computed(output, c.celExpr());
        };
    }

    /** The rules in declared order. */
    List<MapRule> rules() {
        return rules;
    }
}
