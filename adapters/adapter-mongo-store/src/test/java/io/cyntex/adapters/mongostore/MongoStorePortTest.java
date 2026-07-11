package io.cyntex.adapters.mongostore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregated store port binds each store concern to its own distinct, named collection. The
 * driver-bound aggregation itself is witnessed against a real replica-set by {@link MongoStorePortIT};
 * this pins the collection layout deterministically, so a silent rename that would collide two
 * concerns onto one collection fails the build here rather than corrupting a live store.
 */
class MongoStorePortTest {

    @Test
    void bindsEachConcernToItsOwnDistinctNamedCollection() {
        assertThat(List.of(
                MongoStorePort.ARTIFACTS,
                MongoStorePort.PIPELINE_STATE,
                MongoStorePort.CONNECTIONS,
                MongoStorePort.SOURCE_SCHEMAS))
                .doesNotHaveDuplicates()
                .containsExactly("artifacts", "pipeline_state", "connections", "source_schemas");
    }
}
