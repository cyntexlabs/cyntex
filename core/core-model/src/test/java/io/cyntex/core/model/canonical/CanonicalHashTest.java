package io.cyntex.core.model.canonical;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The content hash over a canonical form. It underwrites the apply idempotency key: equal canonical
 * text hashes equal, so re-applying an unchanged resource is a provable no-op. These tests pin the
 * algorithm (SHA-256 over UTF-8, lower-hex, full digest) so a drift reddens instead of silently
 * changing every stored hash.
 */
class CanonicalHashTest {

    @Test
    void hashesUtf8BytesWithSha256AsLowerHex() {
        // The published SHA-256 vector for "abc": pins algorithm + UTF-8 encoding + lower-hex.
        assertThat(CanonicalHash.of("abc"))
                .as("SHA-256 of the UTF-8 bytes, lower-hex")
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void producesA64CharLowerHexDigest() {
        assertThat(CanonicalHash.of("version: cyntex/v1\nkind: source\nid: orders\n"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void hashesMultibyteUtf8CharactersByTheirUtf8Bytes() {
        // A canonical form can carry non-ASCII runtime data (descriptions, config values, table
        // names). The input is "caf" + U+00E9, which hashes over its UTF-8 bytes (63 61 66 c3 a9);
        // the pinned digest is the published SHA-256 of exactly those bytes, so an encoding drift to
        // a single-byte charset (accented char one byte, or unmappable) reddens here.
        assertThat(CanonicalHash.of("caf\u00e9"))
                .isEqualTo("850f7dc43910ff890f8879c0ed26fe697c93a067ad93a7d50f466a7028a9bf4e");
    }

    @Test
    void isStableForEqualInputAndDiffersForDifferentInput() {
        assertThat(CanonicalHash.of("one"))
                .as("stable for equal canonical text")
                .isEqualTo(CanonicalHash.of("one"));
        assertThat(CanonicalHash.of("one"))
                .as("differs when the canonical text differs")
                .isNotEqualTo(CanonicalHash.of("two"));
    }
}
