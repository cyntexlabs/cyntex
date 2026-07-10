package io.cyntex.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.ConnectionTestItem;
import io.cyntex.spi.store.ConnectionTestItem.Status;
import io.cyntex.spi.store.ConnectionTestResult;
import io.cyntex.spi.store.ConnectionTestResult.Outcome;
import io.cyntex.spi.store.ConnectionTester;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The connection-test PDK bridge: {@link PdkConnectionTester} driving a connector's own
 * {@code connectionTest} through the frozen PDK contract and normalizing the streamed test items into a
 * {@link ConnectionTestResult}. A connector that reports a failed check is a normal FAILED result; only
 * a connector that throws out of its test is a coded connector-domain failure. Synthetic connectors
 * compiled at test time prove the drive and the coded-error path without a real connector jar or the
 * PDK runtime.
 */
class PdkConnectionTesterTest {

    private static final long FIXED_MILLIS = 1783939200000L;

    /** A tester over a provisioner that hands back one fixed connector ref, whatever id is asked for. */
    private static ConnectionTester tester(Path jar, String className) {
        ConnectorRef ref = new ConnectorRef(List.of(jar), className, "2.0.8", null);
        ConnectorProvisioner provisioner = connectorId -> ref;
        return new PdkConnectionTester(provisioner, Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC));
    }

    private static ConnectionConfig config() {
        return new ConnectionConfig("conn-1", "demo", Map.of());
    }

    @Test
    void passingConnectorYieldsPassedOutcomeAndStampsIdsAndTime(@TempDir Path dir) {
        ConnectionTester tester = tester(Synthetic.passingTest(dir), "synthetic.PassingTest");

        ConnectionTestResult result = tester.test(config());

        assertThat(result.connectionId()).isEqualTo("conn-1");
        assertThat(result.connectorId()).isEqualTo("demo");
        assertThat(result.testedAt()).isEqualTo(FIXED_MILLIS);
        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result.items()).extracting(ConnectionTestItem::name).containsExactly("Connection");
        assertThat(result.items()).extracting(ConnectionTestItem::status).containsExactly(Status.PASSED);
    }

    @Test
    void aWarningItemDoesNotFailTheOutcomeAndCarriesItsInformationAsMessage(@TempDir Path dir) {
        ConnectionTester tester = tester(Synthetic.warningTest(dir), "synthetic.WarningTest");

        ConnectionTestResult result = tester.test(config());

        assertThat(result.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(result.items()).extracting(ConnectionTestItem::status)
                .containsExactly(Status.PASSED, Status.WARNING);
        ConnectionTestItem warn = result.items().get(1);
        assertThat(warn.name()).isEqualTo("Time detection");
        assertThat(warn.message()).isEqualTo("clock skew 3s");
        assertThat(warn.reason()).isNull();
    }

    @Test
    void aFailedItemMakesTheOutcomeFailedAndMapsTheStructuredDiagnostics(@TempDir Path dir) {
        ConnectionTester tester = tester(Synthetic.failingTest(dir), "synthetic.FailingTest");

        ConnectionTestResult result = tester.test(config());

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.items()).extracting(ConnectionTestItem::name).containsExactly("Connection", "Login");
        ConnectionTestItem login = result.items().get(1);
        assertThat(login.status()).isEqualTo(Status.FAILED);
        assertThat(login.message()).isEqualTo("Login denied");
        assertThat(login.reason()).isEqualTo("bad credentials");
        assertThat(login.solution()).isEqualTo("check username/password");
        assertThat(login.connectorErrorCode()).isEqualTo("28000");
    }

    @Test
    void aConnectorWhoseConnectionTestThrowsIsACodedTestFailure(@TempDir Path dir) {
        ConnectionTester tester = tester(Synthetic.throwingTest(dir), "synthetic.ThrowingTest");

        assertThatThrownBy(() -> tester.test(config()))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException ce = (CyntexException) e;
                    assertThat(ce.code()).isEqualTo(ConnectorError.TEST_FAILED);
                    assertThat(ce.args()).containsEntry("connector", "demo").containsKey("detail");
                });
    }

    @Test
    void anIncompatibleConnectorIsStillRefusedBeforeAnyTestRuns(@TempDir Path dir) {
        // The connection tester provisions through the same open path as the read/write ports: an
        // incompatible declared level is refused up front, never downgraded into a test result.
        ConnectorRef ref = new ConnectorRef(List.of(Synthetic.passingTest(dir)), "synthetic.PassingTest", "2.0.8", "99");
        ConnectionTester tester = new PdkConnectionTester(
                connectorId -> ref, Clock.fixed(Instant.ofEpochMilli(FIXED_MILLIS), ZoneOffset.UTC));

        assertThatThrownBy(() -> tester.test(config()))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> assertThat(((CyntexException) e).code()).isEqualTo(ConnectorError.API_LEVEL_INCOMPATIBLE));
    }
}
