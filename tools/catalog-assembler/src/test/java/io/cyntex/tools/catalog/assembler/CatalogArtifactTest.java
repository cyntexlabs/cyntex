package io.cyntex.tools.catalog.assembler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives and locks the bundled connector catalog. Generation needs the connectors checkout and the
 * derived capability bitmap (produced by catalog-derive, which touches PDK), so the byte-lock runs
 * only in the connector-present refresh job and skips otherwise. The refresh is three steps, each a
 * property-gated run of this class or catalog-derive:
 *
 * <ol>
 *   <li>{@code -Dcyntex.catalog.manifest=<path>} — walk the checkout and write the probe manifest;</li>
 *   <li>catalog-derive reads that manifest, probes, and writes the bitmap;</li>
 *   <li>{@code -Dcyntex.catalog.update -Dcyntex.catalog.bitmap=<path> -Dcyntex.catalog.sha=<sha>} —
 *       regenerate the checked-in catalog (index, per-connector entries) and the ingest report.</li>
 * </ol>
 *
 * Without the update toggle the same step byte-compares the regenerated catalog to the checked-in
 * artifacts, so an upstream drift is caught. Catalog entries embed the connectors repo sha, so the
 * artifacts are stable for a fixed checkout.
 */
class CatalogArtifactTest {

    private static final String INDEX = "index.json";

    private static final boolean UPDATE = Boolean.getBoolean("cyntex.catalog.update");

    @Test
    void emitsTheProbeManifestWhenAskedTo() throws IOException {
        String manifestPath = System.getProperty("cyntex.catalog.manifest");
        assumeTrue(manifestPath != null, "no -Dcyntex.catalog.manifest — not a manifest-emit run, skipping");
        Optional<Path> checkout = connectorsRepo();
        assumeTrue(checkout.isPresent(), "connectors checkout absent — skipping");

        WalkResult walk = ConnectorWalker.walk(checkout.get());
        Files.writeString(Path.of(manifestPath), ManifestWriter.write(walk.sources()));
    }

    @Test
    void generatedCatalogMatchesTheCheckedInArtifacts() throws IOException {
        Optional<Path> checkout = connectorsRepo();
        String bitmapPath = System.getProperty("cyntex.catalog.bitmap");
        assumeTrue(checkout.isPresent(), "connectors checkout absent — skipping");
        assumeTrue(bitmapPath != null, "no -Dcyntex.catalog.bitmap (derive the bitmap first) — skipping");

        Map<String, Set<String>> bitmap = BitmapReader.read(Files.readString(Path.of(bitmapPath)));
        GeneratedCatalog catalog = CatalogGenerator.generate(checkout.get(), resolveSha(), bitmap);

        if (UPDATE) {
            writeArtifacts(catalog);
            return;
        }
        assertCheckedIn(catalog);
    }

    @Test
    void catalogUpdateToggleIsOffDuringNormalRuns() {
        // The regenerate path rewrites the artifacts and skips the byte assertion. Were the toggle set
        // during a normal run, a real catalog regression would be silently rebaselined and pass. This
        // guard makes any run with the toggle RED, so regeneration is always deliberate.
        assertThat(UPDATE)
                .as("cyntex.catalog.update must not be set during a normal run — it rewrites the catalog")
                .isFalse();
    }

    private void writeArtifacts(GeneratedCatalog catalog) throws IOException {
        Path catalogDir = catalogDir();
        Files.createDirectories(catalogDir);
        // Remove stale entries (a connector dropped upstream must not linger), then write fresh.
        for (Path json : jsonFiles()) {
            Files.delete(json);
        }
        Files.writeString(catalogDir.resolve(INDEX), catalog.index());
        for (Map.Entry<String, String> entry : catalog.entries().entrySet()) {
            Files.writeString(catalogDir.resolve(entry.getKey() + ".json"), entry.getValue());
        }
        Files.writeString(reportFile(), catalog.report());
    }

    private void assertCheckedIn(GeneratedCatalog catalog) throws IOException {
        Path catalogDir = catalogDir();
        assertThat(Files.exists(catalogDir.resolve(INDEX)))
                .as("catalog index missing — regenerate with -Dcyntex.catalog.update")
                .isTrue();
        assertThat(Files.readString(catalogDir.resolve(INDEX))).isEqualTo(catalog.index());
        for (Map.Entry<String, String> entry : catalog.entries().entrySet()) {
            Path file = catalogDir.resolve(entry.getKey() + ".json");
            assertThat(Files.exists(file)).as("catalog entry missing: " + file).isTrue();
            assertThat(Files.readString(file)).as("catalog entry drift: " + file).isEqualTo(entry.getValue());
        }
        // Orphan guard: no checked-in entry beyond the regenerated set (mirrors the golden orphan gate).
        Set<String> expected = new TreeSet<>();
        catalog.entries().keySet().forEach(id -> expected.add(id + ".json"));
        Set<String> actual = new TreeSet<>();
        for (Path json : jsonFiles()) {
            actual.add(json.getFileName().toString());
        }
        assertThat(actual).as("stale catalog entries not in the regenerated set").isEqualTo(expected);
        assertThat(Files.readString(reportFile())).as("ingest report drift").isEqualTo(catalog.report());
    }

    /** The {@code <id>.json} entry files (the index is not an entry). */
    private List<Path> jsonFiles() throws IOException {
        Path catalogDir = catalogDir();
        if (!Files.isDirectory(catalogDir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> entries = Files.list(catalogDir)) {
            entries.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals(INDEX))
                    .forEach(files::add);
        }
        return files;
    }

    /** The connectors repo sha that stamps provenance; required when regenerating so the durable
     *  catalog is never poisoned with a sentinel. */
    private static String resolveSha() {
        String sha = System.getProperty("cyntex.catalog.sha");
        if (UPDATE && (sha == null || sha.isBlank())) {
            throw new IllegalStateException(
                    "regeneration requires -Dcyntex.catalog.sha=<connectors repo sha> to stamp provenance");
        }
        return sha == null ? "unknown" : sha;
    }

    /** The runtime bundles the catalog from core-catalog's resources, so the artifacts live there. */
    private static Path catalogDir() {
        return repoRoot().resolve("core").resolve("core-catalog")
                .resolve("src").resolve("main").resolve("resources").resolve("catalog");
    }

    /** The ingest report is a build audit (not bundled into the runtime), kept beside this tool. */
    private static Path reportFile() {
        return repoRoot().resolve("tools").resolve("catalog-assembler").resolve("ingest-report.md");
    }

    /** Walks up to the repo root (the directory holding the core/core-catalog module) so the artifact
     *  paths do not depend on the test's working directory. */
    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("core").resolve("core-catalog"))) {
                return dir;
            }
        }
        throw new IllegalStateException("repo root with core/core-catalog not found above the working directory");
    }

    private static Optional<Path> connectorsRepo() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("tapdata-connectors");
            if (Files.isDirectory(candidate.resolve("connectors"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
