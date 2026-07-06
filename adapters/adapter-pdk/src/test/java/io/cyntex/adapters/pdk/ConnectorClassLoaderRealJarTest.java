package io.cyntex.adapters.pdk;

import io.tapdata.pdk.apis.TapConnector;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The same isolation contract witnessed against two REAL connector jars, gated on system properties
 * so it runs only when a connector dist is supplied and skips during normal builds (which carry no
 * connector jars). Point it at two different connector jars plus their entry class names:
 *
 * <pre>
 *   mvn -pl adapters/adapter-pdk test \
 *     -Dcyntex.pdk.it.jarA=/path/mysql-connector-x.jar   -Dcyntex.pdk.it.classA=io.tapdata.connector.mysql.MysqlConnector \
 *     -Dcyntex.pdk.it.jarB=/path/mongodb-connector-x.jar -Dcyntex.pdk.it.classB=io.tapdata.connector.mongodb.MongodbConnector
 * </pre>
 *
 * The deterministic isolation contract (same-name classes staying distinct, host masking, shared
 * contract, close) is covered by {@link ConnectorClassLoaderTest} with synthetic jars; this adds the
 * real-connector witness the plan calls for.
 */
class ConnectorClassLoaderRealJarTest {

    @Test
    void twoRealConnectorsLoadInIsolationOverOneSharedContract() throws Exception {
        String jarA = System.getProperty("cyntex.pdk.it.jarA");
        String classA = System.getProperty("cyntex.pdk.it.classA");
        String jarB = System.getProperty("cyntex.pdk.it.jarB");
        String classB = System.getProperty("cyntex.pdk.it.classB");
        assumeTrue(jarA != null && classA != null && jarB != null && classB != null,
                "no -Dcyntex.pdk.it.{jarA,classA,jarB,classB} — not a real-connector run, skipping");

        try (ConnectorClassLoader a = ConnectorClassLoader.open(List.of(Path.of(jarA)));
             ConnectorClassLoader b = ConnectorClassLoader.open(List.of(Path.of(jarB)))) {
            Class<? extends TapConnector> ca = a.loadConnectorClass(classA);
            Class<? extends TapConnector> cb = b.loadConnectorClass(classB);

            // Two different connectors, each on its own loader: no cross-pollution.
            assertThat(ca).isNotSameAs(cb);
            assertThat(ca.getClassLoader()).isNotSameAs(cb.getClassLoader());

            // Both bind to the one PDK contract shared from the host, not per-connector copies.
            Class<?> contractViaA = a.load("io.tapdata.pdk.apis.TapConnector");
            Class<?> contractViaB = b.load("io.tapdata.pdk.apis.TapConnector");
            assertThat(contractViaA).isSameAs(TapConnector.class).isSameAs(contractViaB);
        }
    }
}
