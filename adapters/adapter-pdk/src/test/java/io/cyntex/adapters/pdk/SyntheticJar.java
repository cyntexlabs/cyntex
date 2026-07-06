package io.cyntex.adapters.pdk;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Test helper: compiles a single class from source and packages it into a fresh jar, so the class
 * exists ONLY inside that jar (never on the host test classpath). This lets the isolation contract
 * be proven deterministically without any real connector jar. Uses the JDK's own compiler; the build
 * always runs on a JDK, so it is present.
 */
final class SyntheticJar {

    private SyntheticJar() {
    }

    /** Compiles {@code fqcn} from {@code source} and returns a jar containing only that class. */
    static Path compileToJar(Path workDir, String fqcn, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler (these tests need a JDK)");
        }
        try {
            Path srcDir = Files.createDirectories(workDir.resolve("src"));
            Path classesDir = Files.createDirectories(workDir.resolve("classes"));
            Path srcFile = srcDir.resolve(fqcn.replace('.', '/') + ".java");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, source);

            int rc = compiler.run(null, null, null, "-d", classesDir.toString(), srcFile.toString());
            if (rc != 0) {
                throw new IllegalStateException("compiling " + fqcn + " failed (rc=" + rc + ")");
            }

            Path jar = workDir.resolve("connector.jar");
            try (OutputStream os = Files.newOutputStream(jar);
                 JarOutputStream jos = new JarOutputStream(os);
                 Stream<Path> tree = Files.walk(classesDir)) {
                for (Path p : (Iterable<Path>) tree.filter(Files::isRegularFile)::iterator) {
                    String entry = classesDir.relativize(p).toString().replace('\\', '/');
                    jos.putNextEntry(new JarEntry(entry));
                    Files.copy(p, jos);
                    jos.closeEntry();
                }
            }
            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException("building synthetic jar for " + fqcn, e);
        }
    }
}
