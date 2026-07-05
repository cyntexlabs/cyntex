package io.cyntex.adapters.pdk;

import io.cyntex.core.common.CyntexException;
import io.tapdata.entity.codec.TapCodecsRegistry;
import io.tapdata.entity.utils.DataMap;
import io.tapdata.pdk.apis.TapConnector;
import io.tapdata.pdk.apis.context.TapConnectorContext;
import io.tapdata.pdk.apis.functions.ConnectorFunctions;
import io.tapdata.pdk.apis.spec.TapNodeSpecification;

import java.util.Map;

/**
 * An opened, constructed connector plus its registered functions and a driving context — the shared
 * handle the read and write ports drive. Opening resolves the load-time, structural diagnoses into
 * coded connector-domain exceptions: an incompatible API level is refused (never downgraded), and a
 * missing / non-connector entry class and an un-instantiable connector each carry a code. The
 * per-operation drive (init / read / write / stop) and its own coded failures belong to the ports.
 *
 * <p>The connector is driven with its own class loader installed as the thread context loader, the way
 * a real connector reaches its isolated runtime; {@link #close()} closes that loader. Constructing a
 * real connector can bootstrap the PDK runtime, which the host must make reachable — until then this
 * drives synthetic connectors that bind only to the frozen contract.
 */
final class PdkConnector implements AutoCloseable {

    /** A connector-driving action that may throw the connector's own {@code Throwable}. */
    interface Action<T> {
        T run() throws Throwable;
    }

    private final String connectorId;
    private final ConnectorClassLoader loader;
    private final TapConnector connector;
    private final ConnectorFunctions functions;
    private final TapConnectorContext context;

    private PdkConnector(String connectorId, ConnectorClassLoader loader, TapConnector connector,
                         ConnectorFunctions functions, TapConnectorContext context) {
        this.connectorId = connectorId;
        this.loader = loader;
        this.connector = connector;
        this.functions = functions;
        this.context = context;
    }

    /**
     * Loads, level-gates and constructs the connector named by {@code ref}, returning a drivable
     * handle. Throws a coded connector-domain exception for the structural failures: an incompatible
     * API level, a missing / non-connector class, or an un-instantiable connector.
     */
    static PdkConnector open(String connectorId, ConnectorRef ref, Map<String, Object> settings) {
        gateApiLevel(connectorId, ref);

        ConnectorClassLoader loader;
        try {
            loader = ConnectorClassLoader.open(ref.classpath());
        } catch (RuntimeException e) {
            throw loadFailed(connectorId, e);
        }
        boolean opened = false;
        try {
            Class<? extends TapConnector> connectorClass = loadConnectorClass(connectorId, loader, ref.className());
            ClassLoader restore = Thread.currentThread().getContextClassLoader();
            TapConnector connector;
            ConnectorFunctions functions = new ConnectorFunctions();
            try {
                // Construct under the connector's own loader so any context-loader-based PDK lookup
                // resolves against the connector's classpath rather than the host.
                Thread.currentThread().setContextClassLoader(connectorClass.getClassLoader());
                connector = connectorClass.getDeclaredConstructor().newInstance();
                connector.registerCapabilities(functions, new TapCodecsRegistry());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // A connector that will not construct, register, or link (e.g. a missing bundled
                // dependency surfacing as NoClassDefFoundError, or a throwing static initializer) is a
                // diagnosable load failure, not a bare crash. A VM error (out of memory) is not a
                // LinkageError and still crashes bare.
                throw loadFailed(connectorId, e);
            } finally {
                Thread.currentThread().setContextClassLoader(restore);
            }
            TapConnectorContext context = new TapConnectorContext(
                    new TapNodeSpecification(), DataMap.create(settings), null, new SilentLog());
            PdkConnector result = new PdkConnector(connectorId, loader, connector, functions, context);
            opened = true;
            return result;
        } finally {
            // Close the loader on every failure path — a coded load/class error, an escaping VM error,
            // anything — so a construction failure never leaks the connector's open jar handle.
            if (!opened) {
                closeQuietly(loader);
            }
        }
    }

    private static void gateApiLevel(String connectorId, ConnectorRef ref) {
        LevelResolution level = PdkLevelResolver.resolve(ref.pdkApiVersion(), ref.requiredLevel());
        if (level.outcome() == LevelOutcome.INCOMPATIBLE) {
            throw new CyntexException(ConnectorError.API_LEVEL_INCOMPATIBLE,
                    Map.of("connector", connectorId, "required", level.requiredLevel(), "provided", level.engineLevel()),
                    null);
        }
    }

    private static Class<? extends TapConnector> loadConnectorClass(
            String connectorId, ConnectorClassLoader loader, String className) {
        try {
            return loader.loadConnectorClass(className);
        } catch (ClassNotFoundException | IllegalStateException e) {
            throw new CyntexException(ConnectorError.CLASS_NOT_FOUND,
                    Map.of("connector", connectorId, "class", className), e);
        } catch (LinkageError e) {
            // The class is present but does not link (a missing referenced type): a load failure, not
            // an absent class.
            throw loadFailed(connectorId, e);
        }
    }

    private static CyntexException loadFailed(String connectorId, Throwable cause) {
        return new CyntexException(ConnectorError.LOAD_FAILED, Map.of("connector", connectorId), cause);
    }

    String connectorId() {
        return connectorId;
    }

    TapConnector connector() {
        return connector;
    }

    ConnectorFunctions functions() {
        return functions;
    }

    TapConnectorContext context() {
        return context;
    }

    /** Runs {@code action} with the connector's own loader installed as the thread context loader. */
    <T> T underLoader(Action<T> action) throws Throwable {
        ClassLoader restore = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(connector.getClass().getClassLoader());
            return action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(restore);
        }
    }

    /** Stops the connector, swallowing failures — used on the cleanup path where the real error already won. */
    void stopQuietly() {
        try {
            underLoader(() -> {
                connector.stop(context);
                return null;
            });
        } catch (Throwable ignore) {
            // best-effort cleanup: a stop failure must not mask the outcome that is already being returned or thrown
        }
    }

    @Override
    public void close() {
        closeQuietly(loader);
    }

    private static void closeQuietly(ConnectorClassLoader loader) {
        try {
            loader.close();
        } catch (RuntimeException ignore) {
            // best-effort: the loader is being discarded; a close failure must not mask the real error
        }
    }
}
