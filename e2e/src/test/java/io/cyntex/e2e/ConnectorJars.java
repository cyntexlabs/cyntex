package io.cyntex.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds the runtime jar a specification asks to register.
 *
 * <p>Connector jars are not in this repository and are not published where the build can fetch them,
 * so the directory holding them is named from outside - the same shape the product uses for its own
 * seed directory. A specification that names a connector it cannot be given fails saying exactly
 * that, rather than registering nothing and failing later for a reason that reads like a product bug.
 */
final class ConnectorJars {

    /** Names the directory holding connector runtime jars. */
    private static final String DIRECTORY_PROPERTY = "cyntex.e2e.connectors-dir";

    private ConnectorJars() {
    }

    /** The bytes of the jar whose file name begins with the connector id. */
    static byte[] bytesFor(String connectorId) {
        Path directory = directory();
        Path jar = find(directory, connectorId);
        try {
            return Files.readAllBytes(jar);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the " + connectorId + " connector at " + jar, e);
        }
    }

    private static Path directory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new EnvelopeException(
                    "this specification registers a connector, but no " + DIRECTORY_PROPERTY
                            + " says where connector jars are");
        }
        Path directory = Path.of(configured);
        if (!Files.isDirectory(directory)) {
            throw new EnvelopeException(DIRECTORY_PROPERTY + " names " + directory + ", which is not a directory");
        }
        return directory;
    }

    private static Path find(Path directory, String connectorId) {
        try (var entries = Files.list(directory)) {
            List<Path> matches = entries
                    .filter(path -> path.getFileName().toString().startsWith(connectorId))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
            if (matches.isEmpty()) {
                throw new EnvelopeException("no " + connectorId + " connector jar in " + directory);
            }
            // Two jars for one id would make the registered one depend on directory order, and a
            // specification cannot be reproducible if which product it tests is decided that way.
            if (matches.size() > 1) {
                throw new EnvelopeException(
                        "more than one " + connectorId + " connector jar in " + directory + ": " + matches);
            }
            return matches.getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
    }
}
