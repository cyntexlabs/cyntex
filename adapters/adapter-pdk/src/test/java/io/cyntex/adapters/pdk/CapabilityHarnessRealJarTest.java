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
 * The regression the plan calls for: run the live harness against REAL database connector jars and
 * confirm the capabilities it derives at runtime resolve to the same source modes and sink capability
 * the offline build recorded in the bundled catalog. Gated on system properties, so it runs only when
 * a connector dist is supplied and skips during normal builds (which carry no connector jars). One
 * connector (A) is required; a second (B) is optional. Point it at a database connector, its entry
 * class and its catalog id:
 *
 * <pre>
 *   mvn -pl adapters/adapter-pdk test \
 *     -Dcyntex.pdk.it.jarA=/path/mongodb-connector-x.jar -Dcyntex.pdk.it.classA=io.tapdata.mongodb.MongodbConnector -Dcyntex.pdk.it.idA=mongodb \
 *     # optionally a second: -Dcyntex.pdk.it.jarB=... -Dcyntex.pdk.it.classB=... -Dcyntex.pdk.it.idB=...
 * </pre>
 *
 * <p>Supplying {@code cyntex.pdk.it.jarA} activates the {@code pdk-it} profile, which puts the PDK
 * runtime on the host test classpath — a connector's base class bootstraps the runtime through the
 * host class loader, so it must be host-reachable, not inside the isolated connector loader. No extra
 * flag is needed for the runtime.
 *
 * <p>Pick connectors whose catalog modes are derived (databases): their modes come from the
 * registered capabilities alone, so a live probe reproduces them. A connector with declared modes
 * (a message system, SaaS or file) would carry modes the probe cannot see, by design.
 *
 * <p>A connector dist jar is a thin plugin — it bundles neither the PDK runtime (provided on the host
 * by the profile) nor, usually, its own extra native/driver libraries when those are not shaded in.
 * {@code cyntex.pdk.it.runtimeCp} is an optional {@link File#pathSeparator}-joined list of such extra
 * connector libraries; it joins the isolated connector loader's classpath. It is not where the PDK
 * runtime comes from.
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
        assumeTrue(jarA != null && classA != null && idA != null,
                "no -Dcyntex.pdk.it.{jarA,classA,idA} — not a real-connector run, skipping");

        List<Path> runtimeCp = classpathEntries(System.getProperty("cyntex.pdk.it.runtimeCp"));
        CyntexCatalog catalog = CyntexCatalog.load();
        assertLiveDerivationMatchesSnapshot(catalog, jarA, classA, idA, runtimeCp);

        // A second connector is optional; supply -Dcyntex.pdk.it.{jarB,classB,idB} to witness another.
        String jarB = System.getProperty("cyntex.pdk.it.jarB");
        String classB = System.getProperty("cyntex.pdk.it.classB");
        String idB = System.getProperty("cyntex.pdk.it.idB");
        if (jarB != null && classB != null && idB != null) {
            assertLiveDerivationMatchesSnapshot(catalog, jarB, classB, idB, runtimeCp);
        }
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
