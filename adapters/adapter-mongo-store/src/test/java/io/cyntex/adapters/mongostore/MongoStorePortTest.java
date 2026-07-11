package io.cyntex.adapters.mongostore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregated store port binds each store concern to its own distinct, named storage area (a
 * collection, or a GridFS bucket for the connector registry). The driver-bound aggregation itself is
 * witnessed against a real replica-set by {@link MongoStorePortIT}; this pins the layout
 * deterministically, so a silent rename that would collide two concerns onto one name fails the build
 * here rather than corrupting a live store.
 */
class MongoStorePortTest {

    @Test
    void bindsEachConcernToItsOwnDistinctNamedStorage() {
        assertThat(List.of(
                MongoStorePort.ARTIFACTS,
                MongoStorePort.PIPELINE_STATE,
                MongoStorePort.CONNECTIONS,
                MongoStorePort.SOURCE_SCHEMAS,
                MongoStorePort.CONNECTOR_ARTIFACTS))
                .doesNotHaveDuplicates()
                .containsExactly("artifacts", "pipeline_state", "connections", "source_schemas", "connector_artifacts");
    }
}
