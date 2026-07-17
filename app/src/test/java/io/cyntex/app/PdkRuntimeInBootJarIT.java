package io.cyntex.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards that the PDK runtime is actually packaged into the shipped boot jar, not merely present on a
 * test classpath. A real connector's base class bootstraps TapRuntime through the host loader at
 * construction, so a server whose boot jar omits the runtime cannot run any real connector — yet the
 * reachability witness that drives a real connector would stay green either way, because a test fork
 * carries runtime- and test-scope entries alike. This inspects the packaged artifact directly:
 * downgrading the runner to test scope, or dropping it as an unused dependency, removes it from
 * BOOT-INF/lib and reds here. It runs on every build that packages the boot jar (no database, no external
 * jar); it skips only when no boot jar was produced (a non-packaging invocation).
 */
class PdkRuntimeInBootJarIT {

    private static final String BOOT_JAR_PROPERTY = "cyntex.app.boot-jar";
    private static final String RUNTIME_LIB_PREFIX = "BOOT-INF/lib/tapdata-pdk-runner-";

    @Test
    void theBootJarBundlesThePdkRuntime() throws Exception {
        String bootJar = System.getProperty(BOOT_JAR_PROPERTY);
        assumeTrue(bootJar != null && Files.isRegularFile(Path.of(bootJar)),
                "no packaged boot jar — not a packaging build, skipping");

        boolean bundled = false;
        try (JarFile jar = new JarFile(bootJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(RUNTIME_LIB_PREFIX) && name.endsWith(".jar")) {
                    bundled = true;
                    break;
                }
            }
        }
        assertThat(bundled)
                .as("tapdata-pdk-runner bundled in %s (a real connector bootstraps its runtime through the host)", bootJar)
                .isTrue();
    }
}
