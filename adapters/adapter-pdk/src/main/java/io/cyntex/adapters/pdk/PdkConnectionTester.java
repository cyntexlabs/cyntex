package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.ConnectionTestItem;
import io.cyntex.spi.store.ConnectionTestResult;
import io.cyntex.spi.store.ConnectionTestResult.Outcome;
import io.cyntex.spi.store.ConnectionTester;
import io.tapdata.pdk.apis.entity.TestItem;
import io.tapdata.pdk.apis.exception.TapTestItemException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The PDK implementation of the connection-test port: it provisions a connector, refuses it with a code
 * if it will not load or its declared API level is incompatible, drives the connector's own
 * {@code connectionTest} through the frozen PDK contract, and normalizes each streamed test item into a
 * {@link ConnectionTestItem}. The connector creates and releases its own connection inside
 * {@code connectionTest}, so the drive neither inits nor stops the node around it — cleanup is only
 * closing the connector's isolated class loader.
 *
 * <p>A connector that reports a failed check is a normal result whose overall {@link Outcome} is FAILED,
 * not an exception; a warning check never fails the outcome. Only a failure that prevents the test from
 * running — the connector cannot be loaded / level-gated, or throws out of {@code connectionTest} —
 * surfaces as a coded connector-domain exception. A connector's own per-item diagnostic code is carried
 * through verbatim as an opaque display string, taking no part in the first-party error-code system.
 * The PDK types stay inside this class; the port and the result carry none of them.
 */
public final class PdkConnectionTester implements ConnectionTester {

    private final ConnectorProvisioner provisioner;
    private final Clock clock;

    public PdkConnectionTester(ConnectorProvisioner provisioner, Clock clock) {
        this.provisioner = provisioner;
        this.clock = clock;
    }

    @Override
    public ConnectionTestResult test(ConnectionConfig config) {
        PdkConnector connector = PdkConnector.open(
                config.connectorId(), provisioner.resolve(config.connectorId()), config.settings());
        try {
            List<TestItem> raw = new ArrayList<>();
            drive(connector, raw::add);
            List<ConnectionTestItem> items = translate(raw);
            Outcome outcome = anyFailed(items) ? Outcome.FAILED : Outcome.PASSED;
            return new ConnectionTestResult(config.id(), config.connectorId(), outcome, items, clock.millis());
        } finally {
            connector.close();
        }
    }

    /**
     * Drives the connector's own connectionTest under its class loader, collecting each streamed item. A
     * connector that throws out of connectionTest could not complete the test — a coded connector-domain
     * failure, distinct from a connector that reports a failed check (which is a normal FAILED result).
     */
    private static void drive(PdkConnector connector, Consumer<TestItem> sink) {
        try {
            connector.underLoader(() -> {
                connector.connector().connectionTest(connector.context(), sink::accept);
                return null;
            });
        } catch (CyntexException e) {
            throw e;
        } catch (Throwable t) {
            throw new CyntexException(ConnectorError.TEST_FAILED,
                    Map.of("connector", connector.connectorId(), "detail", detail(t)), t);
        }
    }

    private static List<ConnectionTestItem> translate(List<TestItem> raw) {
        List<ConnectionTestItem> items = new ArrayList<>(raw.size());
        for (TestItem item : raw) {
            items.add(translate(item));
        }
        return items;
    }

    /**
     * Normalizes one PDK test item. Diagnostics come from the item's structured exception when present,
     * falling back to its free-text information for the message; the exception's own code is passed
     * through as the opaque connector-error-code display string.
     */
    private static ConnectionTestItem translate(TestItem item) {
        TapTestItemException ex = item.getTapTestItemException();
        String message = firstNonBlank(ex != null ? ex.getMessage() : null, item.getInformation());
        String reason = ex != null ? ex.getReason() : null;
        String solution = ex != null ? ex.getSolution() : null;
        String connectorErrorCode = ex != null ? ex.getErrorCode() : null;
        return new ConnectionTestItem(item.getItem(), status(item.getResult()),
                message, reason, solution, connectorErrorCode);
    }

    /**
     * Maps the connector's result int to a status. The frozen contract defines exactly three values
     * (success / success-with-warning / failed); any other value is treated as a failure, since only the
     * two success codes count as a pass.
     */
    private static ConnectionTestItem.Status status(int result) {
        return switch (result) {
            case TestItem.RESULT_SUCCESSFULLY -> ConnectionTestItem.Status.PASSED;
            case TestItem.RESULT_SUCCESSFULLY_WITH_WARN -> ConnectionTestItem.Status.WARNING;
            default -> ConnectionTestItem.Status.FAILED;
        };
    }

    /** The overall test fails when any check failed; a warning alone does not fail it. */
    private static boolean anyFailed(List<ConnectionTestItem> items) {
        for (ConnectionTestItem item : items) {
            if (item.status() == ConnectionTestItem.Status.FAILED) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static String detail(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
