package io.cyntex.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The constant-pool pre-filter: {@link ClassFileAnnotationScanner} decides whether a compiled class
 * bears an annotation from its bytes alone — no classloading — so self-scan can narrow a jar's
 * classes to the handful worth loading. It reads the constant pool for the annotation's type
 * descriptor; a real classload later confirms the match.
 */
class ClassFileAnnotationScannerTest {

    private static final String TAP_CONNECTOR =
            "Lio/tapdata/pdk/apis/annotations/TapConnectorClass;";

    @Test
    void detectsAnAnnotationTheClassBears(@TempDir Path dir) {
        byte[] bytes = SyntheticJar.classBytes(dir, "synthetic.Annotated",
                "package synthetic;"
                        + "@io.tapdata.pdk.apis.annotations.TapConnectorClass(\"foo-spec.json\")"
                        + "public class Annotated {}");

        assertThat(ClassFileAnnotationScanner.bearsAnnotation(bytes, TAP_CONNECTOR)).isTrue();
    }

    @Test
    void rejectsAClassThatDoesNotBearTheAnnotation(@TempDir Path dir) {
        byte[] bytes = SyntheticJar.classBytes(dir, "synthetic.Plain",
                "package synthetic; public class Plain {}");

        assertThat(ClassFileAnnotationScanner.bearsAnnotation(bytes, TAP_CONNECTOR)).isFalse();
    }

    @Test
    void walksPastLongAndDoubleConstantsThatTakeTwoPoolSlots(@TempDir Path dir) {
        // Long and Double entries occupy two constant-pool slots; a walk that advances by one on them
        // misreads every following entry. The annotation must still be found past them.
        byte[] bytes = SyntheticJar.classBytes(dir, "synthetic.WideConstants",
                "package synthetic;"
                        + "@io.tapdata.pdk.apis.annotations.TapConnectorClass(\"foo-spec.json\")"
                        + "public class WideConstants {"
                        + "  static final long L = 123456789012345L;"
                        + "  static final double D = 2.71828d;"
                        + "}");

        assertThat(ClassFileAnnotationScanner.bearsAnnotation(bytes, TAP_CONNECTOR)).isTrue();
    }
}
