package io.cyntex.adapters.transform;

import io.cyntex.spi.transform.TransformPort;

/**
 * The factory for the stateless row-transform ports. Each entry point takes only the serializable
 * shape of a transform (an expression string, a projection spec) and returns the port that runs it,
 * so the app assembly root can capture that shape in the Jet supplier and build the port member-side.
 */
public final class StatelessTransforms {

    private StatelessTransforms() {
    }

    /** The {@code filter} port for a CEL predicate over the event envelope. */
    public static TransformPort filter(String expr) {
        return new FilterPort(expr);
    }

    /** The {@code map} port for a field projection captured as a {@link MapSpec}. */
    public static TransformPort map(MapSpec spec) {
        return new MapPort(spec);
    }

    /** The {@code js} port for a GraalVM script captured as its source text. */
    public static TransformPort js(String script) {
        return new JsPort(script);
    }
}
