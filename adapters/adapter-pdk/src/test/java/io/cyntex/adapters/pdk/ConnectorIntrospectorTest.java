package io.cyntex.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectorCapabilities;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Self-scan: {@link ConnectorIntrospector} reads a connector artifact and reports its entry class, the
 * spec its {@code @TapConnectorClass} annotation names (path and content), and its declared PDK API
 * version — enough to build a {@link ConnectorRef} and hand the spec to catalog normalization, without
 * being told the entry class up front. Synthetic jars compiled at test time stand in for real
 * connector dist jars.
 */
class ConnectorIntrospectorTest {

    @Test
    void introspectsAConnectorJarIntoItsEntryClassSpecAndApiVersion(@TempDir Path dir) {
        Path jar = Synthetic.annotatedConnector(dir);

        IntrospectedConnector connector = new ConnectorIntrospector().introspect(List.of(jar));

        assertThat(connector.className()).isEqualTo("synthetic.OrdersConnector");
        assertThat(connector.specPath()).isEqualTo("orders-spec.json");
        assertThat(connector.spec()).isEqualTo("{\"id\":\"orders\"}");
        assertThat(connector.pdkApiVersion()).isEqualTo("1.3.5");
    }

    @Test
    void refusesAnArtifactWithNoConnectorClass(@TempDir Path dir) {
        Path jar = Synthetic.jarWithoutConnectorClass(dir);

        assertThatThrownBy(() -> new ConnectorIntrospector().introspect(List.of(jar)))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> assertThat(((CyntexException) e).code())
                        .isEqualTo(ConnectorError.NO_CONNECTOR_CLASS));
    }

    @Test
    void refusesAnArtifactWithMoreThanOneUnrelatedConnectorClass(@TempDir Path dir) {
        Path jar = Synthetic.twoDistinctConnectors(dir);

        assertThatThrownBy(() -> new ConnectorIntrospector().introspect(List.of(jar)))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> assertThat(((CyntexException) e).code())
                        .isEqualTo(ConnectorError.AMBIGUOUS_CONNECTOR_CLASS));
    }

    @Test
    void refusesAConnectorWhoseAnnotationNamesAMissingSpec(@TempDir Path dir) {
        Path jar = Synthetic.annotatedConnectorMissingSpec(dir);

        assertThatThrownBy(() -> new ConnectorIntrospector().introspect(List.of(jar)))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> assertThat(((CyntexException) e).code())
                        .isEqualTo(ConnectorError.SPEC_NOT_FOUND));
    }

    @Test
    void refusesAConnectorWhoseAnnotationBytesAreCorrupt(@TempDir Path dir) {
        // The class scans and links, but its annotation attribute bytes are corrupt, so reading the
        // annotation reflectively fails. That is a defective artifact to refuse with a code — never an
        // error that escapes and takes the caller down.
        Path jar = Synthetic.corruptAnnotationConnector(dir);

        assertThatThrownBy(() -> new ConnectorIntrospector().introspect(List.of(jar)))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> assertThat(((CyntexException) e).code()).isEqualTo(ConnectorError.LOAD_FAILED));
    }

    @Test
    void keepsTheMostDerivedWhenAConnectorSubclassesAnAnnotatedBase(@TempDir Path dir) {
        Path jar = Synthetic.baseAndVariantConnector(dir);

        IntrospectedConnector connector = new ConnectorIntrospector().introspect(List.of(jar));

        assertThat(connector.className()).isEqualTo("synthetic.VariantConnector");
        assertThat(connector.specPath()).isEqualTo("variant-spec.json");
    }

    @Test
    void theIntrospectedFactsDriveCapabilityDerivation(@TempDir Path dir) {
        // The point of self-scan: turn an artifact into a ConnectorRef the rest of the bridge can drive
        // without being told the entry class. Introspect, build the ref, derive its capabilities.
        Path jar = Synthetic.annotatedEmittingConnector(dir);
        IntrospectedConnector introspected = new ConnectorIntrospector().introspect(List.of(jar));

        ConnectorRef ref = new ConnectorRef(
                List.of(jar), introspected.className(), introspected.pdkApiVersion(), null);
        ConnectorCapabilities caps = new PdkCapabilityDeriver(id -> ref).derive("reader");

        assertThat(caps.capabilityIds()).containsExactly("batch_read_function");
    }
}
