package io.cyntex.e2e;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Packages the harness's own connector into the artifact shape the product registers: an annotated
 * entry class, the specification its annotation names, and a manifest declaring the PDK API version.
 *
 * <p>The class is compiled by the build like any other, so the compiler checks it and a reader can read
 * it; this only puts the compiled form into a jar. What it packages is the connector's whole package
 * directory rather than a list of class names - a class compiles into more files than its own when it
 * nests anything, and a jar missing one of them fails at construction time as a load failure, which
 * reads like a product refusal rather than a packaging mistake. Taking the directory cannot miss a
 * file, so that failure cannot happen. The package holds the connector and nothing else, which
 * {@link E2eConnectorJarTest} pins.
 */
final class E2eConnectorJar {

    /** The connector id: the identity the product files the artifact under, declared by the spec. */
    static final String CONNECTOR_ID = "e2e_file";

    /** The entry class's package, packaged whole. */
    private static final String PACKAGE_PATH = "io/cyntex/e2e/connector/";

    /** The resource the entry class's {@code @TapConnectorClass} names, resolved as a jar entry. */
    private static final String SPEC_ENTRY = "e2e-file-spec.json";

    /** {@code properties.id} is the identity registration files the artifact under. */
    private static final String SPEC_JSON = "{\"properties\":{\"id\":\"" + CONNECTOR_ID + "\"}}";

    /**
     * The API version the product's level table registers. An unregistered version does not refuse the
     * artifact - it escapes every guard on the way to a bare crash - so this is not a value to pick
     * loosely.
     */
    private static final String PDK_API_VERSION = "2.0.8";

    private E2eConnectorJar() {
    }

    /** Writes the connector jar into {@code directory} and returns it, named so the id finds it. */
    static Path buildInto(Path directory) {
        Path jar = directory.resolve(CONNECTOR_ID + ".jar");
        Path classes = packageDirectory();
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, manifest())) {
            for (Path file : classFiles(classes)) {
                jos.putNextEntry(new JarEntry(PACKAGE_PATH + file.getFileName()));
                Files.copy(file, jos);
                jos.closeEntry();
            }
            jos.putNextEntry(new JarEntry(SPEC_ENTRY));
            jos.write(SPEC_JSON.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("building the " + CONNECTOR_ID + " connector jar at " + jar, e);
        }
        return jar;
    }

    /** Every compiled file of the connector's package, in a fixed order so the jar is reproducible. */
    private static List<Path> classFiles(Path classes) {
        try (Stream<Path> tree = Files.list(classes)) {
            List<Path> files = new ArrayList<>(tree.filter(Files::isRegularFile).sorted().toList());
            if (files.isEmpty()) {
                throw new IllegalStateException("the connector package at " + classes + " compiled to nothing");
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("listing the connector package at " + classes, e);
        }
    }

    /**
     * The connector's compiled package, found through the class loader rather than by naming a build
     * directory - the same lookup wherever the tests run from.
     */
    private static Path packageDirectory() {
        URL url = E2eConnectorJar.class.getClassLoader().getResource(PACKAGE_PATH);
        if (url == null) {
            throw new IllegalStateException("the connector package " + PACKAGE_PATH + " is not on this classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the connector package resolved to an unusable location: " + url, e);
        }
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(new Attributes.Name("PDK-API-Version"), PDK_API_VERSION);
        return manifest;
    }
}
