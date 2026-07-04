package io.cyntex.adapters.pdk;

import io.cyntex.core.catalog.ConnectorCatalogEntry;
import io.cyntex.core.catalog.CyntexCatalog;
import io.cyntex.core.catalog.DerivedCapability;
import io.cyntex.core.catalog.ModeResolver;
import io.cyntex.core.model.SourceMode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The regression the plan calls for: run the live harness against two REAL database connector jars
 * and confirm the capabilities it derives at runtime resolve to the same source modes and sink
 * capability the offline build recorded in the bundled catalog. Gated on system properties, so it
 * runs only when a connector dist is supplied and skips during normal builds (which carry no
 * connector jars). Point it at two database connectors, their entry classes and their catalog ids:
 *
 * <pre>
 *   mvn -pl adapters/adapter-pdk test \
 *     -Dcyntex.pdk.it.jarA=/path/mysql-connector-x.jar   -Dcyntex.pdk.it.classA=io.tapdata.connector.mysql.MysqlConnector   -Dcyntex.pdk.it.idA=mysql \
 *     -Dcyntex.pdk.it.jarB=/path/mongodb-connector-x.jar -Dcyntex.pdk.it.classB=io.tapdata.connector.mongodb.MongodbConnector -Dcyntex.pdk.it.idB=mongodb \
 *     -Dcyntex.pdk.it.runtimeCp=/path/tapdata-pdk-runner.jar   # + any connector lib entries, {@link File#pathSeparator}-joined
 * </pre>
 *
 * <p>Pick connectors whose catalog modes are derived (databases): their modes come from the
 * registered capabilities alone, so a live probe reproduces them. A connector with declared modes
 * (a message system, SaaS or file) would carry modes the probe cannot see, by design.
 *
 * <p>A connector dist jar is a thin plugin — it does not bundle the PDK runtime — so its base class
 * cannot initialize on the jar alone. Point {@code runtimeCp} at the PDK runtime (and any connector
 * lib directory) and it joins the isolated loader's classpath, keeping the runtime off the host,
 * which carries only the frozen contract.
 *
 * <p>The deterministic mechanism is covered by {@link CapabilityHarnessTest} with synthetic jars;
 * this adds the real-connector witness against the checked-in snapshot.
 */
class CapabilityHarnessRealJarTest {

    @Test
    void liveDerivedCapabilitiesMatchTheBundledCatalogForRealDatabaseConnectors() {
        String jarA = System.getProperty("cyntex.pdk.it.jarA");
        String classA = System.getProperty("cyntex.pdk.it.classA");
        String idA = System.getProperty("cyntex.pdk.it.idA");
        String jarB = System.getProperty("cyntex.pdk.it.jarB");
        String classB = System.getProperty("cyntex.pdk.it.classB");
        String idB = System.getProperty("cyntex.pdk.it.idB");
        assumeTrue(jarA != null && classA != null && idA != null
                        && jarB != null && classB != null && idB != null,
                "no -Dcyntex.pdk.it.{jarA,classA,idA,jarB,classB,idB} — not a real-connector run, skipping");

        List<Path> runtimeCp = classpathEntries(System.getProperty("cyntex.pdk.it.runtimeCp"));
        CyntexCatalog catalog = CyntexCatalog.load();
        assertLiveDerivationMatchesSnapshot(catalog, jarA, classA, idA, runtimeCp);
        assertLiveDerivationMatchesSnapshot(catalog, jarB, classB, idB, runtimeCp);
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

    private static void assertLiveDerivationMatchesSnapshot(CyntexCatalog catalog, String jar,
                                                            String connectorClass, String id,
                                                            List<Path> runtimeCp) {
        ConnectorCatalogEntry snapshot = catalog.byId(id);
        List<Path> classpath = new ArrayList<>();
        classpath.add(Path.of(jar));
        classpath.addAll(runtimeCp);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(classpath)) {
            Set<String> live = CapabilityHarness.deriveCapabilities(loader, connectorClass);
            Set<DerivedCapability> derived = DerivedCapability.fromCapabilityIds(live);

            Set<SourceMode> liveModes = ModeResolver.resolve(derived, null).modes();
            assertThat(liveModes)
                    .as("live-derived source modes for %s", id)
                    .containsExactlyInAnyOrderElementsOf(snapshot.modes());

            boolean liveSink = derived.contains(DerivedCapability.WRITE_RECORD);
            assertThat(liveSink)
                    .as("live-derived sink capability for %s", id)
                    .isEqualTo(snapshot.sink().capable());
        }
    }
}
