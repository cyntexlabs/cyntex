package io.cyntex.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cyntex.core.common.CyntexException;
import io.cyntex.spi.store.ConnectorRegistration;
import io.cyntex.spi.store.RegistrationOutcome;
import io.cyntex.spi.store.RegistrationSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Registration by introspection: {@link ConnectorArtifactRegistrar} turns a connector artifact on
 * disk into one register-if-absent call — self-scan supplies the entry class and PDK API version,
 * the spec's {@code properties.id} supplies the connector id, and the artifact bytes go to the
 * distribution store. The startup seed sweep and the explicit register operation both stand on this
 * one path, differing only in the {@link RegistrationSource} they record.
 */
class ConnectorArtifactRegistrarTest {

    @Test
    void registersAConnectorArtifactUnderItsSpecDeclaredId(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.seedableOrdersConnector(dir);
        InMemoryConnectorRegistry registry = new InMemoryConnectorRegistry();

        RegistrationOutcome outcome = registrarOver(registry).register(jar, RegistrationSource.SEED);

        assertThat(outcome.newlyRegistered()).isTrue();
        ConnectorRegistration registration = outcome.registration();
        assertThat(registration.connectorId()).isEqualTo("orders");
        assertThat(registration.pdkApiVersion()).isEqualTo("1.3.5");
        assertThat(registration.source()).isEqualTo(RegistrationSource.SEED);
        assertThat(registry.artifact(registration.contentHash())).contains(Files.readAllBytes(jar));
    }

    @Test
    void reRegisteringTheSameArtifactBytesIsANoOp(@TempDir Path dir) {
        Path jar = Synthetic.seedableOrdersConnector(dir);
        InMemoryConnectorRegistry registry = new InMemoryConnectorRegistry();
        ConnectorArtifactRegistrar registrar = registrarOver(registry);

        registrar.register(jar, RegistrationSource.SEED);
        RegistrationOutcome again = registrar.register(jar, RegistrationSource.SEED);

        assertThat(again.newlyRegistered()).isFalse();
        assertThat(registry.list()).hasSize(1);
    }

    @Test
    void refusesAnArtifactWhoseSpecIsNotJson(@TempDir Path dir) {
        Path jar = Synthetic.unparsableSpecConnector(dir);
        ConnectorArtifactRegistrar registrar = registrarOver(new InMemoryConnectorRegistry());

        assertThatThrownBy(() -> registrar.register(jar, RegistrationSource.SEED))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException coded = (CyntexException) e;
                    assertThat(coded.code()).isEqualTo(ConnectorError.SPEC_INVALID);
                    assertThat(coded.args()).containsKeys("artifact", "spec", "detail");
                });
    }

    @Test
    void refusesAnArtifactWhoseSpecDeclaresNoPropertiesId(@TempDir Path dir) {
        // This fixture's spec is {"id":"orders"}: valid JSON, but a connector spec carries its
        // identity under properties.id — a top-level id is not one.
        Path jar = Synthetic.annotatedConnector(dir);
        ConnectorArtifactRegistrar registrar = registrarOver(new InMemoryConnectorRegistry());

        assertThatThrownBy(() -> registrar.register(jar, RegistrationSource.SEED))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException coded = (CyntexException) e;
                    assertThat(coded.code()).isEqualTo(ConnectorError.SPEC_INVALID);
                    // The diagnostic must cover an id that is present but unusable (wrong type,
                    // blank), not claim the field is absent when it is visibly there.
                    assertThat(String.valueOf(coded.args().get("detail"))).contains("non-blank string");
                });
    }

    @Test
    void registersFromArtifactBytesJustLikeFromAPath(@TempDir Path dir) throws Exception {
        // The runtime register operation hands the registrar bytes off the wire, not a server path; the
        // bytes entry must land the same registration the on-disk seed path does — same id, same hash.
        Path jar = Synthetic.seedableOrdersConnector(dir);
        byte[] bytes = Files.readAllBytes(jar);
        InMemoryConnectorRegistry registry = new InMemoryConnectorRegistry();

        RegistrationOutcome outcome = registrarOver(registry).register(bytes, RegistrationSource.REGISTER);

        assertThat(outcome.newlyRegistered()).isTrue();
        ConnectorRegistration registration = outcome.registration();
        assertThat(registration.connectorId()).isEqualTo("orders");
        assertThat(registration.pdkApiVersion()).isEqualTo("1.3.5");
        assertThat(registration.source()).isEqualTo(RegistrationSource.REGISTER);
        assertThat(registry.artifact(registration.contentHash())).contains(bytes);
    }

    @Test
    void refusesADifferentArtifactUnderAnAlreadyRegisteredId(@TempDir Path dir) {
        // Same bytes re-registering is a no-op (idempotent by hash); a DIFFERENT artifact claiming an
        // already-registered id is a conflict — selecting among versions is out of scope, so it is
        // refused at register time rather than silently accepted to blow up at load.
        InMemoryConnectorRegistry registry = new InMemoryConnectorRegistry();
        ConnectorArtifactRegistrar registrar = registrarOver(registry);
        RegistrationOutcome first = registrar.register(
                Synthetic.seedableOrdersConnector(dir), RegistrationSource.SEED);

        assertThatThrownBy(() -> registrar.register(
                        Synthetic.conflictingOrdersConnector(dir), RegistrationSource.REGISTER))
                .isInstanceOf(CyntexException.class)
                .satisfies(e -> {
                    CyntexException coded = (CyntexException) e;
                    assertThat(coded.code()).isEqualTo(ConnectorError.REGISTRATION_CONFLICT);
                    assertThat(coded.args()).containsKeys("connector", "existing", "incoming");
                    assertThat(coded.args().get("connector")).isEqualTo("orders");
                    assertThat(coded.args().get("existing")).isEqualTo(first.registration().contentHash());
                });
        // The conflicting artifact is not stored: the store still holds exactly the first registration.
        assertThat(registry.list()).hasSize(1);
    }

    @Test
    void requiresItsCollaboratorsAndArguments(@TempDir Path dir) {
        InMemoryConnectorRegistry registry = new InMemoryConnectorRegistry();
        ConnectorIntrospector introspector = new ConnectorIntrospector();

        assertThatNullPointerException().isThrownBy(() -> new ConnectorArtifactRegistrar(null, introspector));
        assertThatNullPointerException().isThrownBy(() -> new ConnectorArtifactRegistrar(registry, null));

        ConnectorArtifactRegistrar registrar = new ConnectorArtifactRegistrar(registry, introspector);
        assertThatNullPointerException().isThrownBy(() -> registrar.register((Path) null, RegistrationSource.SEED));
        assertThatNullPointerException().isThrownBy(() -> registrar.register(dir.resolve("x.jar"), null));
        assertThatNullPointerException().isThrownBy(() -> registrar.register((byte[]) null, RegistrationSource.SEED));
        assertThatNullPointerException().isThrownBy(() -> registrar.register(new byte[0], null));
    }

    private static ConnectorArtifactRegistrar registrarOver(InMemoryConnectorRegistry registry) {
        return new ConnectorArtifactRegistrar(registry, new ConnectorIntrospector());
    }
}
