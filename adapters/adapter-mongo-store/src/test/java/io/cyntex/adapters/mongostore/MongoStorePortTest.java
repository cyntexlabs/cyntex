package io.cyntex.adapters.mongostore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregated store port binds the four store concerns to four distinct, named collections. The
 * driver-bound aggregation itself is witnessed against a real replica-set by {@link MongoStorePortIT};
 * this pins the collection layout deterministically, so a silent rename that would collide two
 * concerns onto one collection fails the build here rather than corrupting a live store.
 */
class MongoStorePortTest {

    @Test
    void bindsTheFourConcernsToFourDistinctNamedCollections() {
        assertThat(List.of(
                MongoStorePort.ARTIFACTS,
                MongoStorePort.PIPELINE_STATE,
                MongoStorePort.PIPELINE_DESIRED,
                MongoStorePort.CONNECTIONS))
                .doesNotHaveDuplicates()
                .containsExactly("artifacts", "pipeline_state", "pipeline_desired", "connections");
    }
}
