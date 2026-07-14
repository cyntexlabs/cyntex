package io.cyntex.adapters.pdk;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Test helper: compiles a class from source and packages it into a fresh jar, so the class exists
 * ONLY inside that jar (never on the host test classpath). This lets the isolation and self-scan
 * contracts be proven deterministically without any real connector jar. Uses the JDK's own compiler;
 * the build always runs on a JDK, so it is present.
 */
final class SyntheticJar {

    private SyntheticJar() {
    }

    /** Compiles {@code fqcn} from {@code source} and returns a jar containing only that class. */
    static Path compileToJar(Path workDir, String fqcn, String source) {
        return compileToJar(workDir, fqcn, source, Map.of(), Map.of());
    }

    /**
     * Compiles {@code fqcn} from {@code source} and returns a jar containing that class, the given
     * {@code resources} (entry path to text content, e.g. a {@code *-spec.json}) and a manifest
     * carrying the given main attributes (e.g. {@code PDK-API-Version}), the way a real connector dist
     * jar is shaped.
     */
    static Path compileToJar(Path workDir, String fqcn, String source,
                             Map<String, String> resources, Map<String, String> manifestAttributes) {
        Path work = freshWorkDir(workDir);
        Path classesDir = compile(work, fqcn, source);
        Path jar = work.resolve("connector.jar");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os, manifest(manifestAttributes));
             Stream<Path> tree = Files.walk(classesDir)) {
            for (Path p : (Iterable<Path>) tree.filter(Files::isRegularFile)::iterator) {
                String entry = classesDir.relativize(p).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entry));
                Files.copy(p, jos);
                jos.closeEntry();
            }
            for (Map.Entry<String, String> resource : resources.entrySet()) {
                jos.putNextEntry(new JarEntry(resource.getKey()));
                jos.write(resource.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("building synthetic jar for " + fqcn, e);
        }
        return jar;
    }

    /** Packages the given raw entry bytes into a fresh jar with the given manifest main attributes. */
    static Path jarWithEntries(Path workDir, Map<String, byte[]> entries, Map<String, String> manifestAttributes) {
        Path work = freshWorkDir(workDir);
        Path jar = work.resolve("connector.jar");
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os, manifest(manifestAttributes))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("building synthetic jar from raw entries", e);
        }
        return jar;
    }

    /** Compiles {@code fqcn} from {@code source} and returns the raw bytes of its {@code .class} file. */
    static byte[] classBytes(Path workDir, String fqcn, String source) {
        Path classesDir = compile(freshWorkDir(workDir), fqcn, source);
        Path classFile = classesDir.resolve(fqcn.replace('.', '/') + ".class");
        try {
            return Files.readAllBytes(classFile);
        } catch (IOException e) {
            throw new UncheckedIOException("reading compiled class " + fqcn, e);
        }
    }

    /** Compiles {@code fqcn} into a fresh {@code classes} dir under {@code work} and returns that dir. */
    private static Path compile(Path work, String fqcn, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler (these tests need a JDK)");
        }
        try {
            Path srcDir = Files.createDirectories(work.resolve("src"));
            Path classesDir = Files.createDirectories(work.resolve("classes"));
            Path srcFile = srcDir.resolve(fqcn.replace('.', '/') + ".java");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, source);

            int rc = compiler.run(null, null, null, "-d", classesDir.toString(), srcFile.toString());
            if (rc != 0) {
                throw new IllegalStateException("compiling " + fqcn + " failed (rc=" + rc + ")");
            }
            return classesDir;
        } catch (IOException e) {
            throw new UncheckedIOException("compiling " + fqcn, e);
        }
    }

    private static Manifest manifest(Map<String, String> mainAttributes) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttributes.forEach((name, value) -> attributes.put(new Attributes.Name(name), value));
        return manifest;
    }

    /** A private subdirectory per call, so several connectors can be built under one temp dir. */
    private static Path freshWorkDir(Path workDir) {
        try {
            return Files.createTempDirectory(Files.createDirectories(workDir), "syn");
        } catch (IOException e) {
            throw new UncheckedIOException("creating synthetic work dir under " + workDir, e);
        }
    }
}
