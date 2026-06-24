package io.cyntex.tools.catalog.assembler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import io.cyntex.core.catalog.CatalogJson;

/**
 * Walks a tapdata-connectors checkout offline (no classloading) and classifies every candidate
 * module under {@code connectors/} and {@code connectors-javascript/}: a Java connector resolves to
 * the class and canonical spec named by its {@code @TapConnectorClass} annotation; a JavaScript
 * connector to its conventional {@code spec.json} with no class; known non-connector modules and
 * modules with no resolvable spec are exempted with a reason. Non-module directories (no
 * {@code src/main}) are skipped. Every other spec.json in a multi-spec module is set aside, so
 * nothing is silently dropped. Output is deterministic (connectors sorted by id).
 */
final class ConnectorWalker {

    private static final List<String> CONTAINERS = List.of("connectors", "connectors-javascript");

    /** Modules that carry specs but are not publishable connectors (test harnesses, mocks, demos,
     *  shared libraries). Setting them aside explicitly keeps them out of the catalog without a
     *  silent drop. */
    private static final Set<String> EXCLUDED = Set.of(
            "tdd-connector",
            "mock-source-connector",
            "mock-target-connector",
            "demo-connector",
            "coding-demo-connector",
            "js-core");

    private ConnectorWalker() {
    }

    static WalkResult walk(Path connectorsRepoRoot) {
        List<ConnectorSource> sources = new ArrayList<>();
        List<Exemption> exemptions = new ArrayList<>();
        for (String container : CONTAINERS) {
            Path dir = connectorsRepoRoot.resolve(container);
            if (Files.isDirectory(dir)) {
                for (Path module : subdirectories(dir)) {
                    classify(connectorsRepoRoot, module, sources, exemptions);
                }
            }
        }
        sources.sort(Comparator.comparing(ConnectorSource::id));
        return new WalkResult(sources, exemptions);
    }

    private static void classify(Path root, Path module, List<ConnectorSource> sources,
                                 List<Exemption> exemptions) {
        String name = module.getFileName().toString();
        if (!Files.isDirectory(module.resolve("src").resolve("main"))) {
            return; // not a Maven module (dist/, build/, ...); not a connector by construction
        }
        if (EXCLUDED.contains(name)) {
            exemptions.add(new Exemption(Exemption.Category.EXCLUDED, name, "known non-connector module"));
            return;
        }
        Path resources = module.resolve("src").resolve("main").resolve("resources");
        Set<String> referencedSpecs = new TreeSet<>();

        List<TapConnectorRef> refs = scanJavaRefs(module);
        if (!refs.isEmpty()) {
            for (TapConnectorRef ref : refs) {
                referencedSpecs.add(ref.specFile());
                ingest(root, name, resources.resolve(ref.specFile()), ref.classFqn(), false,
                        sources, exemptions);
            }
        } else if (Files.exists(resources.resolve("spec.json"))) {
            referencedSpecs.add("spec.json");
            ingest(root, name, resources.resolve("spec.json"), null, true, sources, exemptions);
        } else {
            exemptions.add(new Exemption(Exemption.Category.NO_CANONICAL_SPEC, name,
                    "no @TapConnectorClass and no spec.json"));
            return;
        }
        for (String other : otherSpecFiles(resources, referencedSpecs)) {
            exemptions.add(new Exemption(Exemption.Category.MULTI_SPEC, name, other));
        }
    }

    private static void ingest(Path root, String module, Path specPath, String classFqn,
                               boolean javascript, List<ConnectorSource> sources,
                               List<Exemption> exemptions) {
        if (!Files.exists(specPath)) {
            exemptions.add(new Exemption(Exemption.Category.NO_CANONICAL_SPEC, module,
                    "annotation names missing spec " + specPath.getFileName()));
            return;
        }
        String id = readId(specPath);
        if (id == null || id.isBlank()) {
            exemptions.add(new Exemption(Exemption.Category.MISSING_ID, module,
                    specPath.getFileName().toString()));
            return;
        }
        String relativeSpec = root.relativize(specPath).toString().replace('\\', '/');
        sources.add(new ConnectorSource(id, module, relativeSpec, classFqn, javascript));
    }

    /** Scans every {@code .java} under the module for an active {@code @TapConnectorClass}. */
    private static List<TapConnectorRef> scanJavaRefs(Path module) {
        Path javaRoot = module.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(javaRoot)) {
            return List.of();
        }
        List<TapConnectorRef> refs = new ArrayList<>();
        try (Stream<Path> files = Files.walk(javaRoot)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> TapConnectorAnnotationScanner.scan(read(p)).ifPresent(refs::add));
        } catch (IOException e) {
            throw new UncheckedIOException("walking java sources of " + module, e);
        }
        return refs;
    }

    private static List<String> otherSpecFiles(Path resources, Set<String> referenced) {
        List<String> others = new ArrayList<>();
        if (!Files.isDirectory(resources)) {
            return others;
        }
        try (Stream<Path> files = Files.list(resources)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && isSpecName(n) && !referenced.contains(n))
                    .sorted()
                    .forEach(others::add);
        } catch (IOException e) {
            throw new UncheckedIOException("listing resources of " + resources, e);
        }
        return others;
    }

    /** A spec-looking file name (the canonical or an alternate), so unrelated json (data-type maps,
     *  etc.) is not mistaken for a multi-spec leftover. */
    private static boolean isSpecName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("spec.json") || lower.contains("spec");
    }

    @SuppressWarnings("unchecked")
    private static String readId(Path specPath) {
        Object tree = CatalogJson.parse(read(specPath));
        if (tree instanceof Map<?, ?> root && root.get("properties") instanceof Map<?, ?> props) {
            Object id = ((Map<String, Object>) props).get("id");
            return id instanceof String s ? s : null;
        }
        return null;
    }

    private static List<Path> subdirectories(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + dir, e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }
}
