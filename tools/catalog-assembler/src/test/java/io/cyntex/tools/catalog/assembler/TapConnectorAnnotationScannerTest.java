package io.cyntex.tools.catalog.assembler;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical spec of a Java connector is the file named by its uncommented
 * {@code @TapConnectorClass} annotation, and the connector class is the one bearing it. This reads
 * both from source text (no classloading), so it must ignore commented-out annotations (e.g.
 * bigquery v1) and resolve the fully-qualified class name from the package + class declaration.
 */
class TapConnectorAnnotationScannerTest {

    @Test
    void readsSpecFileAndFullyQualifiedClassFromAnActiveAnnotation() {
        String source = """
                package io.tapdata.connector.mysql;

                import io.tapdata.pdk.apis.annotations.TapConnectorClass;

                @TapConnectorClass("mysql-spec.json")
                public class MysqlConnector extends CommonDbConnector {
                }
                """;

        Optional<TapConnectorRef> ref = TapConnectorAnnotationScanner.scan(source);

        assertThat(ref).contains(new TapConnectorRef("mysql-spec.json",
                "io.tapdata.connector.mysql.MysqlConnector"));
    }

    @Test
    void ignoresACommentedOutAnnotation() {
        String source = """
                package io.tapdata.connector.bigquery;

                //@TapConnectorClass("spec.json")
                public class BigQueryConnector {
                }
                """;

        assertThat(TapConnectorAnnotationScanner.scan(source)).isEmpty();
    }

    @Test
    void ignoresAnAnnotationCommentedWithLeadingWhitespace() {
        String source = """
                package io.tapdata.connector.x;
                    // @TapConnectorClass("old-spec.json")
                public class XConnector {
                }
                """;

        assertThat(TapConnectorAnnotationScanner.scan(source)).isEmpty();
    }

    @Test
    void returnsEmptyWhenThereIsNoAnnotation() {
        String source = """
                package io.tapdata.connector.x;
                public class Helper {
                }
                """;

        assertThat(TapConnectorAnnotationScanner.scan(source)).isEmpty();
    }

    @Test
    void resolvesTheClassThatBearsTheAnnotationNotAnEarlierType() {
        String source = """
                package io.tapdata.connector.bigquery;

                class Helper {
                }

                @TapConnectorClass("spec-v2.json")
                public class BigQueryConnectorV2 {
                }
                """;

        assertThat(TapConnectorAnnotationScanner.scan(source)).contains(
                new TapConnectorRef("spec-v2.json", "io.tapdata.connector.bigquery.BigQueryConnectorV2"));
    }
}
