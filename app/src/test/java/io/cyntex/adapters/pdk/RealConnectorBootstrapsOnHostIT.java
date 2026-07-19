package io.cyntex.adapters.pdk;

import io.tapdata.pdk.apis.functions.ConnectorFunctions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The witness that a real Tapdata connector constructs on the assembly root's own runtime classpath.
 * A connector dist jar is a thin plugin whose base class bootstraps the PDK runtime (TapRuntime) through
 * the host class loader the moment it is instantiated; the host must therefore carry the runtime. This
 * drives the production open path — introspect the artifact, gate its API level, load and construct it —
 * against a real jar, from the deliverable module, on the same runtime dependency the shipped server
 * carries. (A test fork's classpath is a superset of the shipped one, so this alone does not prove the
 * runtime is packaged into the boot jar; a sibling guard on the packaged boot jar locks that separately.)
 * Constructing the connector needs no database: the runtime is bootstrapped at construction, before any
 * connect, so this stays a pure reachability witness.
 *
 * <p>It lives in the bridge's own package on purpose. The open path stops, database-free, at the
 * package-private {@code PdkConnector.open} — the exact seam a real connector reaches through, and the
 * one no test had ever driven with a real jar. Reaching it from the deliverable module (rather than
 * duplicating its logic) is why this test declares the bridge's package: the class it must call is
 * package-private, and on the flat classpath a same-package test can call it.
 *
 * <p>Gated on {@code -Dcyntex.pdk.it.jarA} (a second is optional via {@code -Dcyntex.pdk.it.jarB}), so
 * the default build — which carries no connector jars — skips it and stays green. Point it at a real
 * database connector dist:
 *
 * <pre>
 *   mvn -pl app verify \
 *     -Dcyntex.pdk.it.jarA=/path/mysql-connector.jar \
 *     -Dcyntex.pdk.it.jarB=/path/mongodb-connector.jar
 * </pre>
 *
 * <p>A thin plugin bundles neither the PDK runtime (provided on the host) nor, when they are not shaded
 * in, its own driver libraries. {@code -Dcyntex.pdk.it.runtimeCp} is an optional
 * {@link File#pathSeparator}-joined list of such extra libraries; it joins the isolated connector
 * loader's classpath, never the host's, and is not where the runtime comes from.
 */
class RealConnectorBootstrapsOnHostIT {

    @Test
    void aRealConnectorConstructsOnTheHostRuntimeClasspath() {
        String jarA = System.getProperty("cyntex.pdk.it.jarA");
        assumeTrue(jarA != null, "no -Dcyntex.pdk.it.jarA — not a real-connector run, skipping");

        List<Path> runtimeCp = classpathEntries(System.getProperty("cyntex.pdk.it.runtimeCp"));
        assertConnectorBootstraps(jarA, runtimeCp);

        String jarB = System.getProperty("cyntex.pdk.it.jarB");
        if (jarB != null) {
            assertConnectorBootstraps(jarB, runtimeCp);
        }
    }

    /**
     * Introspects the artifact the production way (no entry class supplied), builds its ref, and drives
     * the production open path. The connector constructing at all is the reachability witness: its base
     * class bootstraps TapRuntime through the host loader during construction, so a non-null connector
     * means the host made the runtime reachable. Its registered read capability confirms the real
     * connector's own registration ran, not merely an empty construct.
     */
    private static void assertConnectorBootstraps(String jar, List<Path> runtimeCp) {
        Path jarPath = Path.of(jar);
        String id = idOf(jarPath);

        List<Path> classpath = new ArrayList<>();
        classpath.add(jarPath);
        classpath.addAll(runtimeCp);

        IntrospectedConnector introspected = new ConnectorIntrospector().introspect(classpath);
        ConnectorRef ref = new ConnectorRef(
                classpath, introspected.className(), introspected.pdkApiVersion(), null);

        try (PdkConnector connector = PdkConnector.open(id, ref, Map.of())) {
            assertThat(connector.connector())
                    .as("real connector %s constructed on the host runtime classpath", id)
                    .isNotNull();
            assertThat(connector.connectorId()).isEqualTo(id);

            ConnectorFunctions functions = connector.functions();
            assertThat(functions.getBatchReadFunction())
                    .as("registered snapshot-read capability for source connector %s", id)
                    .isNotNull();
        }
    }

    /** A stable label for the connector from its jar file name (drives error text and the id assertion). */
    private static String idOf(Path jar) {
        String name = jar.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Splits a {@link File#pathSeparator}-joined classpath, or an empty list when none is supplied. */
    private static List<Path> classpathEntries(String joined) {
        List<Path> entries = new ArrayList<>();
        if (joined != null && !joined.isBlank()) {
            for (String entry : joined.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    entries.add(Path.of(entry));
                }
            }
        }
        return entries;
    }
}
