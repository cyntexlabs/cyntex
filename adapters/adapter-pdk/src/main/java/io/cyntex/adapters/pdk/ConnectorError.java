package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexErrorCode;
import io.cyntex.core.common.Severity;

import java.util.Set;

/**
 * The {@code connector} domain's error codes: the first-party, diagnosable failures the PDK bridge
 * raises when it loads, level-gates, drives or projects a connector. These are user-facing — the
 * operator pointed the bridge at a connector jar that will not load, declared a class that is not a
 * connector, requires a newer PDK API than the bridge provides, or produced an event the codec could
 * not project.
 *
 * <p>These are two-segment {@code connector.<symbol>} codes and go through the full build-time gate
 * set like any other first-party domain. They are deliberately distinct from the reserved
 * {@code connector.<connector-id>.<symbol>} namespace a connector's OWN thrown codes occupy: those
 * carry a connector-id segment, are longer by construction, and are deduped at runtime rather than at
 * build time (they come from jars the build cannot see).
 *
 * <p>Programmer errors / invariant violations stay bare and are allowed to crash — they are not
 * laundered into a {@code connector.*} code that would hide the defect. {@code placeholders()} is the
 * named-argument contract: every throw site supplies a value for each name, and the build-time
 * placeholder gate checks the catalog templates against it.
 */
public enum ConnectorError implements CyntexErrorCode {

    /**
     * The connector jar or its classpath could not be opened / linked. {@code connector} is the
     * connector id being loaded.
     */
    LOAD_FAILED("connector.load-failed", Set.of("connector")),

    /**
     * The declared entry class is not on the connector's classpath or is not a connector.
     * {@code connector} is the connector id; {@code class} is the class name that was looked up.
     */
    CLASS_NOT_FOUND("connector.class-not-found", Set.of("connector", "class")),

    /**
     * The connector requires a newer PDK API level than the bridge provides, so it is refused rather
     * than silently downgraded. {@code connector} is the connector id; {@code required} is the level
     * it asked for; {@code provided} is the level the bridge provides.
     */
    API_LEVEL_INCOMPATIBLE("connector.api-level-incompatible", Set.of("connector", "required", "provided")),

    /**
     * A PDK event could not be projected to or from the cyntex envelope. {@code connector} is the
     * connector id; {@code detail} is the projection failure.
     */
    PROJECTION_FAILED("connector.projection-failed", Set.of("connector", "detail")),

    /**
     * The connector failed while reading (snapshot or cdc). {@code connector} is the connector id;
     * {@code detail} is the failure the connector reported.
     */
    CAPTURE_FAILED("connector.capture-failed", Set.of("connector", "detail")),

    /**
     * The connector's own connection test could not be run to completion — the connector threw out of
     * {@code connectionTest} rather than reporting a failed check. A reported failed check is a normal
     * FAILED result, not this code. {@code connector} is the connector id; {@code detail} is the failure
     * the connector reported.
     */
    TEST_FAILED("connector.test-failed", Set.of("connector", "detail")),

    /**
     * The connector's schema discovery could not be run to completion — the connector threw out of
     * {@code discoverSchema}. {@code connector} is the connector id; {@code detail} is the failure the
     * connector reported.
     */
    DISCOVER_FAILED("connector.discover-failed", Set.of("connector", "detail")),

    /**
     * The connector failed while writing a batch. {@code connector} is the connector id;
     * {@code detail} is the failure the connector reported.
     */
    WRITE_FAILED("connector.write-failed", Set.of("connector", "detail"));

    private final String code;
    private final Set<String> placeholders;

    ConnectorError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
