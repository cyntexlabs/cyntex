package io.cyntex.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The best-effort scan that discovers existing {@code kind: source} ids in a directory, so the
 * pipeline wizard can offer them as choices. It must skip pipelines, other kinds, and any file that
 * fails to parse — a junk neighbour must never break scaffolding.
 */
class WorkspaceSourcesTest {

    @Test
    void collectsOnlySourceIdsSkippingOtherKindsAndUnparseableFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("src_b.cyn.yml"),
                "version: cyntex/v1\nkind: source\nid: src_b\nconnector: mysql\n");
        Files.writeString(dir.resolve("tgt_a.cyn.yml"),
                "version: cyntex/v1\nkind: source\nid: tgt_a\nconnector: postgres\n");
        Files.writeString(dir.resolve("pipe.cyn.yml"),
                "version: cyntex/v1\nkind: pipeline\nid: pipe\nsource: src_b\n"
                        + "serve:\n  from: /.*/\n  sync:\n    - source: tgt_a\n");
        Files.writeString(dir.resolve("bogus.cyn.yml"),
                "version: cyntex/v1\nkind: nonsense\nid: x\n");

        // ids returned sorted, only the two sources, pipeline + bogus dropped
        assertThat(WorkspaceSources.idsIn(dir)).containsExactly("src_b", "tgt_a");
    }

    @Test
    void returnsEmptyForADirectoryWithNoSources(@TempDir Path dir) {
        assertThat(WorkspaceSources.idsIn(dir)).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheDirectoryDoesNotExist(@TempDir Path dir) {
        // an --out that hasn't been created yet must not crash discovery
        assertThat(WorkspaceSources.idsIn(dir.resolve("not-created"))).isEmpty();
    }

    @Test
    void discoversReusableDefinitionsByKind(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mask_pii.cyn.yml"),
                "version: cyntex/v1\nkind: transform\nid: mask_pii\ntype: map\n"
                        + "fields:\n  ssn: false\n");
        Files.writeString(dir.resolve("src_a.cyn.yml"),
                "version: cyntex/v1\nkind: source\nid: src_a\nconnector: mysql\n");

        assertThat(WorkspaceSources.idsOfKind(dir, "transform")).containsExactly("mask_pii");
        assertThat(WorkspaceSources.idsOfKind(dir, "source")).containsExactly("src_a");
    }

    @Test
    void skipsAFileWithMalformedYaml(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("good.cyn.yml"),
                "version: cyntex/v1\nkind: source\nid: good\nconnector: mysql\n");
        Files.writeString(dir.resolve("broken.cyn.yml"), "version: [unterminated\nkind: source\n");

        assertThat(WorkspaceSources.idsIn(dir)).containsExactly("good");
    }
}
