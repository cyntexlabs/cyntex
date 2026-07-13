package io.cyntex.spi.store;

import io.cyntex.core.model.Resource;
import io.cyntex.core.model.SourceResource;
import io.cyntex.core.model.canonical.CanonicalHash;
import io.cyntex.core.model.canonical.CanonicalWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Witnesses the atomic versioned-mutation contract independently of a storage adapter. */
class ArtifactStoreTest {

    private static final CanonicalWriter WRITER = new CanonicalWriter();

    @Test
    void versionedMutationsUseCanonicalHashesAndLeaveStaleWritesUnapplied() {
        ContractStore store = new ContractStore();
        Resource source = source("localhost");
        Resource changed = source("replica");
        Resource changedAgain = source("stale-writer");
        String oldHash = hash(source);
        String newHash = hash(changed);

        assertThat(store.create(source)).isEqualTo(ArtifactMutation.CREATED);
        assertThat(store.create(source)).isEqualTo(ArtifactMutation.ALREADY_EXISTS);
        assertThat(store.replace("orders", oldHash, changed)).isEqualTo(ArtifactMutation.REPLACED);

        String canonicalAfterReplace = store.storedCanonical("orders");
        assertThat(store.replace("orders", oldHash, changedAgain))
                .isEqualTo(ArtifactMutation.VERSION_CONFLICT);
        assertThat(store.storedCanonical("orders")).isEqualTo(canonicalAfterReplace);

        assertThat(store.delete("orders", oldHash)).isEqualTo(ArtifactMutation.VERSION_CONFLICT);
        assertThat(store.storedCanonical("orders")).isEqualTo(canonicalAfterReplace);

        assertThat(store.delete("orders", newHash)).isEqualTo(ArtifactMutation.DELETED);
        assertThat(store.delete("orders", newHash)).isEqualTo(ArtifactMutation.NOT_FOUND);
    }

    @Test
    void replacementIdMustMatchAndAMismatchLeavesCanonicalBytesUnchanged() {
        ContractStore store = new ContractStore();
        Resource source = source("localhost");
        Resource differentId = source("customers", "replica");
        store.create(source);
        String canonicalBeforeReplace = store.storedCanonical("orders");

        assertThatThrownBy(() -> store.replace("orders", hash(source), differentId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.storedCanonical("orders")).isEqualTo(canonicalBeforeReplace);
    }

    private static Resource source(String host) {
        return source("orders", host);
    }

    private static Resource source(String id, String host) {
        return new SourceResource(id, null, "mysql", Map.of("host", host),
                null, null, null, null, null);
    }

    private static String hash(Resource artifact) {
        return CanonicalHash.of(WRITER.write(artifact));
    }

    /** Minimal canonical-byte store used only to witness the SPI's required outcomes. */
    private static final class ContractStore implements ArtifactStore {

        private final Map<String, Resource> resources = new LinkedHashMap<>();

        @Override
        public void saveAll(List<Resource> artifacts) {
            for (Resource artifact : artifacts) {
                resources.put(artifact.id(), artifact);
            }
        }

        @Override
        public ArtifactMutation create(Resource artifact) {
            return resources.putIfAbsent(artifact.id(), artifact) == null
                    ? ArtifactMutation.CREATED
                    : ArtifactMutation.ALREADY_EXISTS;
        }

        @Override
        public ArtifactMutation replace(String id, String expectedContentHash, Resource replacement) {
            if (!id.equals(replacement.id())) {
                throw new IllegalArgumentException("replacement id must equal the artifact id");
            }
            Resource current = resources.get(id);
            if (current == null) {
                return ArtifactMutation.NOT_FOUND;
            }
            if (!hash(current).equals(expectedContentHash)) {
                return ArtifactMutation.VERSION_CONFLICT;
            }
            resources.put(id, replacement);
            return ArtifactMutation.REPLACED;
        }

        @Override
        public ArtifactMutation delete(String id, String expectedContentHash) {
            Resource current = resources.get(id);
            if (current == null) {
                return ArtifactMutation.NOT_FOUND;
            }
            if (!hash(current).equals(expectedContentHash)) {
                return ArtifactMutation.VERSION_CONFLICT;
            }
            resources.remove(id);
            return ArtifactMutation.DELETED;
        }

        @Override
        public Optional<Resource> get(String id) {
            return Optional.ofNullable(resources.get(id));
        }

        @Override
        public List<Resource> list() {
            return new ArrayList<>(resources.values());
        }

        private String storedCanonical(String id) {
            return WRITER.write(resources.get(id));
        }
    }
}
