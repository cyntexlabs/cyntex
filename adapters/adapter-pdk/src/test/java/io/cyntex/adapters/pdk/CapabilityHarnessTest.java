package io.cyntex.adapters.pdk;

import io.cyntex.core.catalog.DerivedCapability;
import io.cyntex.core.catalog.ModeResolution;
import io.cyntex.core.catalog.ModeResolver;
import io.cyntex.core.catalog.ModeSource;
import io.cyntex.core.catalog.SinkRules;
import io.cyntex.core.model.SourceMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The live capability harness: {@code registerCapabilities} run inside an isolated connector loader.
 * The always-on tests use synthetic connectors compiled at test time (real connector dist jars are
 * not in CI); the derivation-alignment tests confirm the raw ids map to the same catalog modes the
 * offline build records, and that the bridge derives snapshot/cdc/sink but never invents a
 * spec-declared mode (stream / api / file). A gated real-connector regression lives in
 * {@link CapabilityHarnessRealJarTest}.
 */
class CapabilityHarnessTest {

    /**
     * Builds a synthetic connector jar whose {@code registerCapabilities} registers exactly the
     * given source/sink functions, so the derived capability ids are deterministic. The lifecycle
     * methods throw: a capability probe must never call them, so if the harness did, the test fails.
     */
    private static Path connectorJar(Path dir, String simpleName,
                                     boolean batch, boolean stream, boolean write) {
        StringBuilder body = new StringBuilder();
        if (batch) {
            body.append("functions.supportBatchRead((a, b, c, d, e) -> {});");
        }
        if (stream) {
            body.append("functions.supportStreamRead((a, b, c, d, e) -> {});");
        }
        if (write) {
            body.append("functions.supportWriteRecord((a, b, c, d) -> {});");
        }
        String source = ""
                + "package synthetic;"
                + "import io.tapdata.pdk.apis.TapConnector;"
                + "import io.tapdata.pdk.apis.functions.ConnectorFunctions;"
                + "import io.tapdata.entity.codec.TapCodecsRegistry;"
                + "import io.tapdata.pdk.apis.context.TapConnectionContext;"
                + "import io.tapdata.entity.schema.TapTable;"
                + "import io.tapdata.pdk.apis.entity.ConnectionOptions;"
                + "import io.tapdata.pdk.apis.entity.TestItem;"
                + "import java.util.List;"
                + "import java.util.function.Consumer;"
                + "public class " + simpleName + " implements TapConnector {"
                + "  public void registerCapabilities(ConnectorFunctions functions, TapCodecsRegistry codecs) {"
                // The harness must install the connector's own loader as the context loader for the
                // probe (that is how a real connector reaches its isolated runtime); assert it here.
                + "    if (Thread.currentThread().getContextClassLoader() != getClass().getClassLoader()) {"
                + "      throw new AssertionError(\"context loader is not the connector loader during registerCapabilities\");"
                + "    }"
                + body
                + "  }"
                + "  public void init(TapConnectionContext c) { throw new AssertionError(\"init called during capability probe\"); }"
                + "  public void stop(TapConnectionContext c) { throw new AssertionError(\"stop called during capability probe\"); }"
                + "  public void discoverSchema(TapConnectionContext c, List<String> t, int n, Consumer<List<TapTable>> s) { throw new AssertionError(\"discoverSchema called during capability probe\"); }"
                + "  public ConnectionOptions connectionTest(TapConnectionContext c, Consumer<TestItem> s) { throw new AssertionError(\"connectionTest called during capability probe\"); }"
                + "  public int tableCount(TapConnectionContext c) { throw new AssertionError(\"tableCount called during capability probe\"); }"
                + "}";
        return SyntheticJar.compileToJar(dir, "synthetic." + simpleName, source);
    }

    private static boolean isSink(Set<String> capabilityIds) {
        return DerivedCapability.fromCapabilityIds(capabilityIds).contains(DerivedCapability.WRITE_RECORD);
    }

    /** Resolves source modes from live-derived ids the way the catalog does (no spec declaration). */
    private static ModeResolution resolveModes(Set<String> capabilityIds) {
        return ModeResolver.resolve(DerivedCapability.fromCapabilityIds(capabilityIds), null);
    }

    @Test
    void derivesTheCapabilityIdsAConnectorRegisters(@TempDir Path dir) throws Exception {
        Path jar = connectorJar(dir, "FullConnector", true, true, true);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Set<String> caps = CapabilityHarness.deriveCapabilities(loader, "synthetic.FullConnector");
            assertThat(caps)
                    .containsExactly("batch_read_function", "stream_read_function", "write_record_function");
        }
    }

    @Test
    void databaseCapabilitiesDeriveSnapshotCdcAndSink(@TempDir Path dir) throws Exception {
        Path jar = connectorJar(dir, "FullConnector", true, true, true);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Set<String> caps = CapabilityHarness.deriveCapabilities(loader, "synthetic.FullConnector");

            ModeResolution modes = resolveModes(caps);
            assertThat(modes.modes()).containsExactly(SourceMode.CDC, SourceMode.SNAPSHOT);
            assertThat(modes.source(SourceMode.SNAPSHOT)).isEqualTo(ModeSource.DERIVED);
            assertThat(modes.source(SourceMode.CDC)).isEqualTo(ModeSource.DERIVED);
            assertThat(isSink(caps)).isTrue();
        }
    }

    @Test
    void batchOnlyConnectorDerivesSnapshotAndIsNotASink(@TempDir Path dir) throws Exception {
        Path jar = connectorJar(dir, "SnapshotOnly", true, false, false);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Set<String> caps = CapabilityHarness.deriveCapabilities(loader, "synthetic.SnapshotOnly");
            assertThat(caps).containsExactly("batch_read_function");

            ModeResolution modes = resolveModes(caps);
            assertThat(modes.modes()).containsExactly(SourceMode.SNAPSHOT);
            assertThat(isSink(caps)).isFalse();
        }
    }

    @Test
    void streamReadDerivesCdcByDefaultAndInventsNoDeclaredMode(@TempDir Path dir) throws Exception {
        // Stream read derives cdc (the database default). It never derives stream / api / file: those
        // share the same registered id but differ in semantics, so they must be declared in the spec.
        // The bridge reports what was registered and invents nothing.
        Path jar = connectorJar(dir, "StreamOnly", false, true, false);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Set<String> caps = CapabilityHarness.deriveCapabilities(loader, "synthetic.StreamOnly");
            assertThat(caps).containsExactly("stream_read_function");

            ModeResolution modes = resolveModes(caps);
            assertThat(modes.modes()).containsExactly(SourceMode.CDC);
            assertThat(modes.source(SourceMode.CDC)).isEqualTo(ModeSource.DERIVED);
            assertThat(modes.modes())
                    .doesNotContain(SourceMode.STREAM, SourceMode.API, SourceMode.FILE);
        }
    }

    @Test
    void writeOnlyConnectorIsASinkWithNoSourceModes(@TempDir Path dir) throws Exception {
        Path jar = connectorJar(dir, "SinkOnly", false, false, true);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Set<String> caps = CapabilityHarness.deriveCapabilities(loader, "synthetic.SinkOnly");
            assertThat(caps).containsExactly("write_record_function");

            assertThat(resolveModes(caps).modes()).isEmpty();
            assertThat(SinkRules.derive(isSink(caps), null, false).capable()).isTrue();
        }
    }

    @Test
    void aConnectorThatRegistersNothingDerivesNoCapabilities(@TempDir Path dir) throws Exception {
        Path jar = connectorJar(dir, "InertConnector", false, false, false);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Set<String> caps = CapabilityHarness.deriveCapabilities(loader, "synthetic.InertConnector");
            assertThat(caps).isEmpty();
        }
    }

    @Test
    void twoConnectorsInSeparateLoadersDeriveTheirOwnCapabilities(@TempDir Path dir) throws Exception {
        Path full = connectorJar(dir.resolve("full"), "FullConnector", true, true, true);
        Path snapshotOnly = connectorJar(dir.resolve("snap"), "SnapshotOnly", true, false, false);
        try (ConnectorClassLoader a = ConnectorClassLoader.open(List.of(full));
             ConnectorClassLoader b = ConnectorClassLoader.open(List.of(snapshotOnly))) {
            assertThat(CapabilityHarness.deriveCapabilities(a, "synthetic.FullConnector"))
                    .containsExactly("batch_read_function", "stream_read_function", "write_record_function");
            // The second loader derives only its own capability; the first loader's does not bleed in.
            assertThat(CapabilityHarness.deriveCapabilities(b, "synthetic.SnapshotOnly"))
                    .containsExactly("batch_read_function");
        }
    }

    @Test
    void probeRunsUnderTheConnectorLoaderAndRestoresTheContextClassLoader(@TempDir Path dir) throws Exception {
        // The synthetic connector's registerCapabilities throws unless the context loader is its own,
        // so a successful derive proves the harness installed it. The harness must also restore the
        // caller's context loader afterward, leaving the thread as it found it.
        Path jar = connectorJar(dir, "FullConnector", true, false, false);
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            assertThat(CapabilityHarness.deriveCapabilities(loader, "synthetic.FullConnector"))
                    .containsExactly("batch_read_function");
        }
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
    }

    @Test
    void probingAMissingConnectorClassFails(@TempDir Path dir) throws Exception {
        Path jar = connectorJar(dir, "FullConnector", true, false, false);
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            assertThatThrownBy(() -> CapabilityHarness.deriveCapabilities(loader, "synthetic.Missing"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("synthetic.Missing");
        }
    }

    @Test
    void probingANonConnectorClassFails(@TempDir Path dir) throws Exception {
        Path jar = SyntheticJar.compileToJar(dir, "synthetic.Plain",
                "package synthetic; public class Plain {}");
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            // synthetic.Plain is not a TapConnector: the loader's type check refuses it before any probe.
            assertThatThrownBy(() -> CapabilityHarness.deriveCapabilities(loader, "synthetic.Plain"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("synthetic.Plain");
        }
    }
}
