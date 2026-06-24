package io.cyntex.core.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainTest {

    @Test
    void idIsTheLowerCaseName() {
        assertThat(Domain.DSL.id()).isEqualTo("dsl");
        assertThat(Domain.CATALOG.id()).isEqualTo("catalog");
    }

    @Test
    void registryHoldsTheInitialFiveDomains() {
        assertThat(Domain.ids())
                .containsExactlyInAnyOrder("dsl", "cli", "core", "catalog", "schema");
    }

    @Test
    void registeredAcceptsKnownDomains() {
        assertThat(Domain.isRegistered("dsl")).isTrue();
        assertThat(Domain.isRegistered("schema")).isTrue();
    }

    @Test
    void registeredRejectsTyposAndWrongCase() {
        // the legacy "dls." typo that silently minted a new namespace — must be caught now
        assertThat(Domain.isRegistered("dls")).isFalse();
        assertThat(Domain.isRegistered("DSL")).isFalse();
        assertThat(Domain.isRegistered("")).isFalse();
    }
}
