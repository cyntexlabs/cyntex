package io.cyntex.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the published examples live, and how to find them.
 *
 * <p>One home for the location, because two of them would be a place for the executor that runs an
 * example and the gate that checks it to disagree about which file either means - and they would
 * disagree silently, each passing against a different copy.
 *
 * <p>An example is a directory rather than a lone document: a specification names resources by
 * filename and is loaded against a workspace, so the specification and the resources it names are one
 * unit and travel together.
 */
final class Examples {

    /** Resolved against the module directory, so these are the bytes in the working tree, not a build copy. */
    static final Path ROOT = Path.of("examples");

    private static final String SUFFIX = ".e2e.yml";

    private Examples() {}

    /** Every published specification, in a stable order. */
    static List<Path> specifications() {
        try (Stream<Path> tree = Files.walk(ROOT)) {
            return tree.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The workspace an example is loaded against: the directory its specification sits in. */
    static Path workspace(String name) {
        return ROOT.resolve(name);
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
