package io.cyntex.tools.catalog.derive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Entry point of the catalog-derive step: given the probe manifest the PDK-free assembler wrote, the
 * connectors dist directory, and an output path, it reads the worklist, classloads and probes each
 * connector for its capability bitmap, and writes that bitmap for the assembler to merge. This is the
 * one PDK-touching step of the catalog pipeline; it runs in the refresh job (which has the built
 * connector jars), never in the default build. The bitmap it writes is transient.
 */
public final class CatalogDerive {

    private CatalogDerive() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: CatalogDerive <manifest.tsv> <distDir> <bitmap.tsv>");
        }
        EmitOutcome outcome =
                run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), ConnectorCapabilityProbe::probe);
        int total = outcome.bitmap().size() + outcome.skipped().size();
        System.out.printf("derived %d of %d connectors; %d skipped%n",
                outcome.bitmap().size(), total, outcome.skipped().size());
        outcome.skipped().forEach((id, reason) -> System.out.printf("  skipped %s — %s%n", id, reason));
    }

    static EmitOutcome run(Path manifestFile, Path distDir, Path bitmapFile,
                           BiFunction<Path, String, Set<String>> prober) throws IOException {
        List<ManifestEntry> entries = Manifest.parse(Files.readString(manifestFile));
        EmitOutcome outcome = BitmapEmitter.emit(entries, new JarResolver(distDir)::find, prober);
        Files.writeString(bitmapFile, CapabilityBitmap.serialize(outcome.bitmap()));
        return outcome;
    }
}
