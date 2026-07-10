package io.cyntex.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.spi.store.ConnectionTestItem.Status;
import io.cyntex.spi.store.ConnectionTestResult.Outcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectionTestResultTest {

    private static ConnectionTestItem item(String name, Status status) {
        return new ConnectionTestItem(name, status, null, null, null, null);
    }

    @Test
    void carriesIdsOutcomeItemsAndTestedAt() {
        ConnectionTestResult result = new ConnectionTestResult(
                "conn-mongo-orders",
                "mongodb",
                Outcome.PASSED,
                List.of(item("Connection", Status.PASSED)),
                1783939200000L);

        assertThat(result.connectionId()).isEqualTo("conn-mongo-orders");
        assertThat(result.connectorId()).isEqualTo("mongodb");
        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result.items()).extracting(ConnectionTestItem::name).containsExactly("Connection");
        assertThat(result.testedAt()).isEqualTo(1783939200000L);
    }

    @Test
    void nullItemsBecomeEmpty() {
        ConnectionTestResult result = new ConnectionTestResult("c", "mongodb", Outcome.PASSED, null, 1L);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void itemsAreUnmodifiableDefensiveCopyPreservingArrivalOrder() {
        List<ConnectionTestItem> source = new ArrayList<>();
        source.add(item("Connection", Status.PASSED));
        source.add(item("Login", Status.FAILED));
        ConnectionTestResult result = new ConnectionTestResult("c", "mongodb", Outcome.FAILED, source, 1L);

        source.add(item("Read", Status.PASSED)); // a later mutation of the caller's list must not leak in

        assertThat(result.items()).extracting(ConnectionTestItem::name).containsExactly("Connection", "Login");
        assertThatThrownBy(() -> result.items().add(item("x", Status.PASSED)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiresConnectionIdConnectorIdAndOutcome() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionTestResult(null, "mongodb", Outcome.PASSED, List.of(), 1L));
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionTestResult("c", null, Outcome.PASSED, List.of(), 1L));
        assertThatNullPointerException()
                .isThrownBy(() -> new ConnectionTestResult("c", "mongodb", null, List.of(), 1L));
    }

    @Test
    void outcomeIsStoredAsGivenNotDerivedFromItems() {
        // Overall outcome is stored independently; a WARNING item never flips a PASSED outcome to FAILED.
        ConnectionTestResult result = new ConnectionTestResult(
                "c", "mongodb", Outcome.PASSED, List.of(item("Time detection", Status.WARNING)), 1L);

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
    }
}
