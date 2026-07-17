package io.cyntex.adapters.pdk;

import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.utils.cache.KVMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opened-connector handle. A synthetic connector binds to the frozen contract alone, so it opens
 * without the PDK runtime and lets the driving context be checked in the default build.
 */
class PdkConnectorTest {

    private static ConnectorRef ref(Path dir) {
        return new ConnectorRef(
                List.of(Synthetic.discoverableSource(dir)), "synthetic.Discoverable", "2.0.8", null);
    }

    @Test
    void theDrivingContextCarriesAUsableStateMap(@TempDir Path dir) {
        try (PdkConnector connector = PdkConnector.open("demo", ref(dir), Map.of())) {
            // A connector reads and writes per-run scratch through the context's state map during init and
            // discovery; a null one is an NPE the moment it touches one. The map has to be there and work,
            // not merely be present.
            KVMap<Object> stateMap = connector.context().getStateMap();
            assertThat(stateMap).as("driving context state map").isNotNull();
            stateMap.put("cursor", "42");
            assertThat(stateMap.get("cursor")).isEqualTo("42");
        }
    }

    @Test
    void fillsAFieldsPdkTypeFromTheSpecsDataTypesMapping(@TempDir Path dir) {
        // A discovered field carries only its database type ("int"); a connector reads by the PDK type the
        // connector's spec maps it onto. A ref carrying that spec fills the field's tapType, which is null
        // as discovered.
        String spec = "{\"dataTypes\": {\"int\": {\"to\": \"TapNumber\", \"bit\": 32}}}";
        ConnectorRef ref = new ConnectorRef(
                List.of(Synthetic.discoverableSource(dir)), "synthetic.Discoverable", "2.0.8", null, spec);
        try (PdkConnector connector = PdkConnector.open("demo", ref, Map.of())) {
            TapTable table = new TapTable("orders");
            table.add(new TapField("id", "int"));
            assertThat(table.getNameFieldMap().get("id").getTapType()).as("tapType before fill").isNull();

            connector.fillFieldTypes(table);

            assertThat(table.getNameFieldMap().get("id").getTapType()).as("tapType after fill").isNotNull();
        }
    }

    @Test
    void fillFieldTypesLeavesTheFieldsUntouchedForAConnectorWithNoSpec(@TempDir Path dir) {
        // A connector with no spec declares no mapping, so there is nothing to fill and the fields stay as
        // discovered - the tapType null.
        ConnectorRef ref = new ConnectorRef(
                List.of(Synthetic.discoverableSource(dir)), "synthetic.Discoverable", "2.0.8", null);
        try (PdkConnector connector = PdkConnector.open("demo", ref, Map.of())) {
            TapTable table = new TapTable("orders");
            table.add(new TapField("id", "int"));

            connector.fillFieldTypes(table);

            assertThat(table.getNameFieldMap().get("id").getTapType()).isNull();
        }
    }

    @Test
    void theDrivingContextCarriesAUsableGlobalStateMap(@TempDir Path dir) {
        try (PdkConnector connector = PdkConnector.open("demo", ref(dir), Map.of())) {
            // The global state map is the same shape and reached the same way; a connector that touches it
            // during init hits the same null. Provided alongside the per-run map.
            KVMap<Object> globalStateMap = connector.context().getGlobalStateMap();
            assertThat(globalStateMap).as("driving context global state map").isNotNull();
            globalStateMap.put("epoch", "1");
            assertThat(globalStateMap.get("epoch")).isEqualTo("1");
        }
    }
}
