package io.cyntex.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards that the shipped boot jar's manifest opens java.base/java.lang to unnamed modules — the JVM
 * access a real PDK connector needs at construction. A connector binds its configuration through
 * cglib, which reflects into ClassLoader.defineClass; JDK 17+ denies that reflection without an
 * explicit open, so the connector's class initializer fails before it can run. `java -jar
 * app-boot.jar` (the shipped entry point, and the launch the real-process host uses) reads Add-Opens
 * from this manifest before the application starts — so the open must travel with the jar itself, not
 * only on a test fork's argLine. A reachability witness that drives a real connector inside a test
 * fork stays green either way, because the fork carries the open regardless; only the packaged
 * artifact reveals the gap. Runs on every packaging build (no database, no external jar); skips only
 * when no boot jar was produced.
 */
class BootJarDeclaresConnectorHostOpensIT {

    private static final String BOOT_JAR_PROPERTY = "cyntex.app.boot-jar";
    private static final String REQUIRED_OPEN = "java.base/java.lang";

    @Test
    void theBootJarManifestOpensJavaLangForTheConnectorHost() throws Exception {
        String bootJar = System.getProperty(BOOT_JAR_PROPERTY);
        assumeTrue(bootJar != null && Files.isRegularFile(Path.of(bootJar)),
                "no packaged boot jar — not a packaging build, skipping");

        String addOpens;
        try (JarFile jar = new JarFile(bootJar)) {
            Manifest manifest = jar.getManifest();
            addOpens = manifest == null ? null : manifest.getMainAttributes().getValue("Add-Opens");
        }
        assertThat(addOpens)
                .as("Add-Opens in %s manifest (a real connector's cglib config binding reflects into %s)",
                        bootJar, REQUIRED_OPEN)
                .isNotNull()
                .contains(REQUIRED_OPEN);
    }
}
