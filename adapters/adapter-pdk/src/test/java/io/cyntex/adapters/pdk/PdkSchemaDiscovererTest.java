package io.cyntex.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.SchemaDiscoverer;
import io.cyntex.spi.store.SourceField;
import io.cyntex.spi.store.SourceIndex;
import io.cyntex.spi.store.SourceModel;
import io.cyntex.spi.store.SourceTable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The schema-discovery PDK bridge: {@link PdkSchemaDiscoverer} driving a connector's frozen
 * {@code discoverSchema} and normalizing the reported PDK tables into a {@link SourceModel} — table
 * names, fields, primary key and indexes. A connector that throws out of discovery is a coded
 * connector-domain failure. Synthetic connectors compiled at test time prove the drive and the
 * coded-error path without a real connector jar or the PDK runtime.
 */
class PdkSchemaDiscovererTest {

    /** A discoverer over a provisioner that hands back one fixed connector ref, whatever id is asked for. */
    private static SchemaDiscoverer discoverer(Path jar, String className) {
        ConnectorRef ref = new ConnectorRef(List.of(jar), className, "2.0.8", null);
        return new PdkSchemaDiscoverer(connectorId -> ref);
    }

    private static ConnectionConfig config() {
        return new ConnectionConfig("conn-1", "demo", Map.of());
    }

    @Test
    void discoversTablesWithTheirFieldsInOrder(@TempDir Path dir) {
        SchemaDiscoverer discoverer = discoverer(Synthetic.discoverableSource(dir), "synthetic.Discoverable");

        SourceModel model = discoverer.discover(config());

        assertThat(model.tables()).extracting(SourceTable::name).containsExactly("orders");
        SourceTable orders = model.tables().get(0);
        assertThat(orders.fields()).extracting(SourceField::name).containsExactly("id", "amount");
        assertThat(orders.fields()).extracting(SourceField::type).containsExactly("int", "decimal");
    }

    @Test
    void carriesThePrimaryKeyColumns(@TempDir Path dir) {
        SchemaDiscoverer discoverer = discoverer(Synthetic.discoverableSource(dir), "synthetic.Discoverable");

        SourceModel model = discoverer.discover(config());

        assertThat(model.tables().get(0).primaryKey()).containsExactly("id");
    }

    @Test
    void carriesTheIndexesWithTheirFieldsAndUniqueness(@TempDir Path dir) {
        SchemaDiscoverer discoverer = discoverer(Synthetic.discoverableSource(dir), "synthetic.Discoverable");

        SourceModel model = discoverer.discover(config());

        List<SourceIndex> indexes = model.tables().get(0).indexes();
        assertThat(indexes).extracting(SourceIndex::name).containsExactly("idx_amount");
        assertThat(indexes.get(0).fields()).containsExactly("amount");
        assertThat(indexes.get(0).unique()).isFalse();
    }

    @Test
    void aConnectorWhoseDiscoverThrowsIsACodedDiscoverFailure(@TempDir Path dir) {
        SchemaDiscoverer discoverer = discoverer(Synthetic.throwingDiscoverSource(dir), "synthetic.ThrowingDiscover");

        assertThatThrownBy(() -> discoverer.discover(config()))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException ce = (CyntexException) e;
                    assertThat(ce.code()).isEqualTo(ConnectorError.DISCOVER_FAILED);
                    assertThat(ce.args()).containsEntry("connector", "demo").containsKey("detail");
                });
    }

    @Test
    void anIncompatibleConnectorIsRefusedBeforeDiscovery(@TempDir Path dir) {
        // Discovery provisions through the same open path as the read / write / test ports: an
        // incompatible declared level is refused up front, never downgraded into an empty model.
        ConnectorRef ref = new ConnectorRef(
                List.of(Synthetic.discoverableSource(dir)), "synthetic.Discoverable", "2.0.8", "99");
        SchemaDiscoverer discoverer = new PdkSchemaDiscoverer(connectorId -> ref);

        assertThatThrownBy(() -> discoverer.discover(config()))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> assertThat(((CyntexException) e).code()).isEqualTo(ConnectorError.API_LEVEL_INCOMPATIBLE));
    }
}
