package io.cyntex.cli;

import io.cyntex.core.dsl.DslParser;
import io.cyntex.core.model.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Workspace layout enforcement for the authoring verbs — "structure is truth". In a managed
 * workspace every artifact lives under the directory named for its declared kind
 * ({@code <root>/<kind>/<id>.cyn.yml}); a file whose immediate parent directory name does not equal
 * its declared kind is misplaced. Only well-formed artifacts are judged: a file that does not parse
 * is left for the full validator to report, so a malformed neighbour never masquerades as misplaced.
 * This gate is for directory (workspace-mode) validation only — a single named file carries no
 * layout claim and is exempt.
 */
final class WorkspaceLayout {

    private WorkspaceLayout() {
    }

    /** A misplaced artifact: the file, the kind it declares, and the directory name it sits in. */
    record Misplacement(Path file, String declaredKind, String parentDir) {
    }

    /**
     * The first artifact under {@code root} (in filename order) whose immediate parent directory name
     * does not equal its declared kind, or empty when every well-formed artifact is correctly placed.
     */
    static Optional<Misplacement> firstMisplacement(Path root) {
        DslParser parser = new DslParser();
        for (Path file : artifacts(root)) {
            Path parent = file.getParent();
            String parentDir = parent != null ? parent.getFileName().toString() : "";
            String kind;
            try {
                Resource resource = parser.parse(Files.readString(file));
                kind = resource.kind();
            } catch (RuntimeException | IOException notWellFormed) {
                // the full validator owns parse / read faults; the layout gate only judges valid files
                continue;
            }
            if (!parentDir.equals(kind)) {
                return Optional.of(new Misplacement(file, kind, parentDir));
            }
        }
        return Optional.empty();
    }

    private static List<Path> artifacts(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".cyn.yml"))
                    .sorted().toList();
        } catch (IOException unwalkable) {
            // an unreadable tree yields no layout findings; the full validator reports the IO fault
            return List.of();
        }
    }
}
