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
 * <p>One sweep, and no way to name an example by hand. The executor that runs the examples and the gate
 * that parses and validates them both enumerate from here, so the published set and the exercised set
 * are the same set by construction. Were either able to name one directly, the two would drift in
 * silence - each green against a different subset, and an example belonging to neither.
 *
 * <p>An example is a directory rather than a lone document: a specification names resources by
 * filename and is loaded against a workspace, so the specification and the resources it names are one
 * unit and travel together. The workspace is therefore the specification's own parent.
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

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
