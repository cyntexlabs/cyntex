package io.cyntex.tools.catalog.derive;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The refresh-job invocation of catalog-derive: given the manifest the assembler emitted, the
 * connectors dist directory and an output path (all as system properties), it classloads and probes
 * every connector for real and writes the bitmap. Gated on the properties, so it runs only as the
 * deliberate derive step of a refresh and skips during normal builds (which have no connector jars).
 */
class CatalogDeriveRealRunTest {

    @Test
    void derivesTheBitmapFromTheRealCheckoutWhenAskedTo() throws IOException {
        String manifest = System.getProperty("cyntex.derive.manifest");
        String dist = System.getProperty("cyntex.derive.dist");
        String out = System.getProperty("cyntex.derive.out");
        assumeTrue(manifest != null && dist != null && out != null,
                "no -Dcyntex.derive.{manifest,dist,out} — not a real derive run, skipping");

        CatalogDerive.run(Path.of(manifest), Path.of(dist), Path.of(out), ConnectorCapabilityProbe::probe);
    }
}
