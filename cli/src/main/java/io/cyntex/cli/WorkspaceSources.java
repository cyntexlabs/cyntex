package io.cyntex.cli;

import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Best-effort discovery of existing {@code kind: source} ids in a directory, for the pipeline
 * wizard's reference menus. Every {@code *.cyn.yml} is parsed in isolation; non-source kinds and any
 * file that fails to parse are silently skipped — a malformed neighbour must never break scaffolding.
 */
final class WorkspaceSources {

    private WorkspaceSources() {
    }

    /** The sorted ids of every well-formed {@code kind: source} resource found anywhere under {@code dir}. */
    static List<String> idsIn(Path dir) {
        return idsOfKind(dir, "source");
    }

    /** The sorted ids of every well-formed resource of {@code kind} found anywhere under {@code dir}. */
    static List<String> idsOfKind(Path dir, String kind) {
        DslParser parser = new DslParser();
        List<String> ids = new ArrayList<>();
        for (Path file : artifacts(dir)) {
            try {
                Resource resource = parser.parse(Files.readString(file));
                if (resource.kind().equals(kind)) {
                    ids.add(resource.id());
                }
            } catch (RuntimeException | IOException skip) {
                // not a readable, well-formed resource — best-effort discovery ignores it
            }
        }
        ids.sort(null);
        return ids;
    }

    private static List<Path> artifacts(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cyn.yml"))
                    .sorted().toList();
        } catch (IOException unwalkable) {
            // best-effort discovery: an unreadable tree yields no candidates, never a crash
            return List.of();
        }
    }
}
