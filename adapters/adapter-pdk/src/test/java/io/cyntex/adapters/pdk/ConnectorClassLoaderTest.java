package io.cyntex.adapters.pdk;

import io.tapdata.pdk.apis.TapConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConnectorClassLoaderTest {

    /** A connector-shaped class whose {@code tag()} returns the given marker, so two builds differ. */
    private static Path widgetJar(Path dir, String marker) {
        return SyntheticJar.compileToJar(dir, "synthetic.Widget",
                "package synthetic; public class Widget { public String tag() { return \"" + marker + "\"; } }");
    }

    @Test
    void loadsAClassFromTheConnectorJar(@TempDir Path dir) throws Exception {
        Path jar = widgetJar(dir, "A");
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Class<?> widget = loader.load("synthetic.Widget");
            assertThat(widget.getName()).isEqualTo("synthetic.Widget");
            // The class comes from the connector loader, not leaked in from the host.
            assertThat(widget.getClassLoader()).isNotSameAs(getClass().getClassLoader());
        }
    }

    @Test
    void twoConnectorsWithTheSameClassNameStayIsolated(@TempDir Path dir) throws Exception {
        Path jarA = widgetJar(dir.resolve("a"), "A");
        Path jarB = widgetJar(dir.resolve("b"), "B");
        try (ConnectorClassLoader a = ConnectorClassLoader.open(List.of(jarA));
             ConnectorClassLoader b = ConnectorClassLoader.open(List.of(jarB))) {
            Class<?> wa = a.load("synthetic.Widget");
            Class<?> wb = b.load("synthetic.Widget");
            // Same name, different Class objects: neither loader pollutes the other.
            assertThat(wa).isNotSameAs(wb);
            String ta = (String) wa.getMethod("tag").invoke(wa.getDeclaredConstructor().newInstance());
            String tb = (String) wb.getMethod("tag").invoke(wb.getDeclaredConstructor().newInstance());
            assertThat(ta).isEqualTo("A");
            assertThat(tb).isEqualTo("B");
        }
    }

    @Test
    void connectorsCannotSeeHostApplicationClasses(@TempDir Path dir) throws Exception {
        Path jar = widgetJar(dir, "A");
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            // A cyntex class that IS on the host classpath must stay invisible to the connector.
            assertThatThrownBy(() -> loader.load("io.cyntex.adapters.pdk.ConnectorClassLoader"))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void theSharedPdkContractResolvesToTheHostClass(@TempDir Path dir) throws Exception {
        Path jar = widgetJar(dir, "A");
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            // The PDK contract is shared from the host: one TapConnector type, not a per-connector copy.
            Class<?> shared = loader.load("io.tapdata.pdk.apis.TapConnector");
            assertThat(shared).isSameAs(TapConnector.class);
        }
    }

    @Test
    void sharesTheRuntimeCodegenLibraryFromTheHost(@TempDir Path dir) throws Exception {
        // The PDK runtime maps connection config through cglib: it generates a BeanMap subclass and
        // defines it into the connector's own loader (the config bean lives there). That generated class
        // extends net.sf.cglib.beans.BeanMap, so the connector loader must resolve cglib from the host,
        // or a real connector's first config load dies linking the generated class. cglib rides the PDK
        // runtime, present only in the real-connector lane, so this reads through to the host class there
        // and stays out of the runtime-free default build.
        Class<?> hostBeanMap;
        try {
            hostBeanMap = Class.forName("net.sf.cglib.beans.BeanMap");
        } catch (ClassNotFoundException noRuntimeOnDefaultBuild) {
            assumeTrue(false, "cglib is not on the default host classpath; the real-connector lane covers this");
            return;
        }
        Path jar = widgetJar(dir, "A");
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            Class<?> shared = loader.load("net.sf.cglib.beans.BeanMap");
            assertThat(shared).isSameAs(hostBeanMap);
        }
    }

    @Test
    void closeReleasesTheLoader(@TempDir Path dir) throws Exception {
        Path jar = widgetJar(dir, "A");
        ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar));
        loader.close();
        // After close the jar is released; a not-yet-loaded class can no longer be resolved.
        assertThatThrownBy(() -> loader.load("synthetic.Widget"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void loadConnectorClassRejectsANonConnectorClass(@TempDir Path dir) throws Exception {
        Path jar = widgetJar(dir, "A");
        try (ConnectorClassLoader loader = ConnectorClassLoader.open(List.of(jar))) {
            // synthetic.Widget does not implement TapConnector: loading it as a connector is refused.
            assertThatThrownBy(() -> loader.loadConnectorClass("synthetic.Widget"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("synthetic.Widget");
        }
    }
}
