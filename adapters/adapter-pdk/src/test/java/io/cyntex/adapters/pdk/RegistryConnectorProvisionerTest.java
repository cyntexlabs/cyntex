package io.cyntex.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.ConnectorRegistry;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real connector provisioner: {@link RegistryConnectorProvisioner} resolves a connector id against
 * the distribution store, stages its registered bytes into a content-addressed on-disk cache
 * ({@code <hash>.jar}), and introspects the staged jar into the {@link ConnectorRef} the bridge loads
 * from. Staging is content-addressed, so a second resolve reuses the cached jar rather than fetching the
 * bytes again. An id that resolves to no artifact — or to more than one — is refused with a coded
 * connector-domain exception rather than loaded blindly. Synthetic connector jars compiled at test time
 * stand in for real connector dist jars; an in-memory registry stands in for the Mongo/GridFS store.
 */
class RegistryConnectorProvisionerTest {

    @Test
    void resolvesARegisteredConnectorToARefStagedInTheContentAddressedCache(@TempDir Path dir) throws IOException {
        Path cacheDir = dir.resolve("plugins");
        byte[] artifact = Files.readAllBytes(Synthetic.annotatedConnector(dir));
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register("orders", "1.3.5", RegistrationSource.SEED, artifact);

        ConnectorRef ref = new RegistryConnectorProvisioner(registry, new ConnectorIntrospector(), cacheDir)
                .resolve("orders");

        assertThat(ref.className()).isEqualTo("synthetic.OrdersConnector");
        assertThat(ref.pdkApiVersion()).isEqualTo("1.3.5");
        assertThat(ref.requiredLevel()).isNull();
        assertThat(ref.classpath()).hasSize(1);
        Path staged = ref.classpath().get(0);
        assertThat(staged.getParent()).isEqualTo(cacheDir);
        assertThat(staged.getFileName().toString()).endsWith(".jar");
        assertThat(Files.readAllBytes(staged)).isEqualTo(artifact);
    }

    @Test
    void reusesTheStagedArtifactOnASecondResolveInsteadOfFetchingBytesAgain(@TempDir Path dir) throws IOException {
        Path cacheDir = dir.resolve("plugins");
        byte[] artifact = Files.readAllBytes(Synthetic.annotatedConnector(dir));
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register("orders", "1.3.5", RegistrationSource.SEED, artifact);
        RegistryConnectorProvisioner provisioner =
                new RegistryConnectorProvisioner(registry, new ConnectorIntrospector(), cacheDir);

        Path first = provisioner.resolve("orders").classpath().get(0);
        Path second = provisioner.resolve("orders").classpath().get(0);

        assertThat(second).isEqualTo(first);
        assertThat(registry.artifactCalls).isEqualTo(1);
    }

    @Test
    void refusesAConnectorIdWithNoRegisteredArtifact(@TempDir Path dir) {
        RegistryConnectorProvisioner provisioner = new RegistryConnectorProvisioner(
                new InMemoryRegistry(), new ConnectorIntrospector(), dir.resolve("plugins"));

        assertThatThrownBy(() -> provisioner.resolve("ghost"))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException ce = (CyntexException) e;
                    assertThat(ce.code()).isEqualTo(ConnectorError.NOT_REGISTERED);
                    assertThat(ce.args()).containsEntry("connector", "ghost");
                });
    }

    @Test
    void refusesAConnectorIdThatResolvesToMoreThanOneRegisteredArtifact(@TempDir Path dir) throws IOException {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.register("orders", "1.3.5", RegistrationSource.SEED,
                Files.readAllBytes(Synthetic.annotatedConnector(dir)));
        registry.register("orders", "2.0.8", RegistrationSource.REGISTER,
                Files.readAllBytes(Synthetic.annotatedEmittingConnector(dir)));
        RegistryConnectorProvisioner provisioner = new RegistryConnectorProvisioner(
                registry, new ConnectorIntrospector(), dir.resolve("plugins"));

        assertThatThrownBy(() -> provisioner.resolve("orders"))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException ce = (CyntexException) e;
                    assertThat(ce.code()).isEqualTo(ConnectorError.AMBIGUOUS_REGISTRATION);
                    assertThat(ce.args()).containsEntry("connector", "orders").containsKey("artifacts");
                });
    }

    @Test
    void refusesARegistrationWhoseArtifactBytesAreMissing(@TempDir Path dir) {
        InMemoryRegistry registry = new InMemoryRegistry();
        registry.addDanglingRegistration("orders", "0000feedface", "1.3.5");
        RegistryConnectorProvisioner provisioner = new RegistryConnectorProvisioner(
                registry, new ConnectorIntrospector(), dir.resolve("plugins"));

        assertThatThrownBy(() -> provisioner.resolve("orders"))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> assertThat(((CyntexException) e).code()).isEqualTo(ConnectorError.LOAD_FAILED));
    }

    @Test
    void requiresItsCollaborators(@TempDir Path dir) {
        InMemoryRegistry registry = new InMemoryRegistry();
        ConnectorIntrospector introspector = new ConnectorIntrospector();
        Path cacheDir = dir.resolve("plugins");

        assertThatNullPointerException().isThrownBy(
                () -> new RegistryConnectorProvisioner(null, introspector, cacheDir));
        assertThatNullPointerException().isThrownBy(
                () -> new RegistryConnectorProvisioner(registry, null, cacheDir));
        assertThatNullPointerException().isThrownBy(
                () -> new RegistryConnectorProvisioner(registry, introspector, null));
    }

    /**
     * An in-memory {@link ConnectorRegistry} standing in for the Mongo/GridFS distribution store: it
     * hashes bytes content-addressably, registers-if-absent, and counts {@link #artifact} fetches so a
     * test can prove the on-disk cache is reused rather than re-fetched.
     */
    private static final class InMemoryRegistry implements ConnectorRegistry {

        private final List<ConnectorRegistration> registrations = new ArrayList<>();
        private final Map<String, byte[]> bytesByHash = new LinkedHashMap<>();
        int artifactCalls;

        @Override
        public RegistrationOutcome register(
                String connectorId, String pdkApiVersion, RegistrationSource source, byte[] artifact) {
            String hash = sha256(artifact);
            for (ConnectorRegistration existing : registrations) {
                if (existing.contentHash().equals(hash)) {
                    return new RegistrationOutcome(existing, false);
                }
            }
            ConnectorRegistration registration = new ConnectorRegistration(connectorId, hash, pdkApiVersion, source);
            registrations.add(registration);
            bytesByHash.put(hash, artifact.clone());
            return new RegistrationOutcome(registration, true);
        }

        @Override
        public List<ConnectorRegistration> list() {
            return List.copyOf(registrations);
        }

        @Override
        public Optional<byte[]> artifact(String contentHash) {
            artifactCalls++;
            byte[] bytes = bytesByHash.get(contentHash);
            return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
        }

        /** A registration whose bytes were never stored — the store inconsistency the provisioner refuses. */
        void addDanglingRegistration(String connectorId, String contentHash, String pdkApiVersion) {
            registrations.add(new ConnectorRegistration(
                    connectorId, contentHash, pdkApiVersion, RegistrationSource.REGISTER));
        }

        private static String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
