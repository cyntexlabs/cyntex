package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaturityTest {

    // The declaration order is load-bearing: OperationRegistry.exposedOn clips a face surface with
    // stage.compareTo(ceiling) <= 0, so reordering these constants would silently change which
    // operations a face exposes. Pin the order here so such a reorder reddens instead.
    @Test
    void rolloutOrderIsPinned() {
        assertThat(Maturity.values()).containsExactly(Maturity.POC, Maturity.ALPHA, Maturity.BETA, Maturity.GA);
        assertThat(Maturity.POC).isLessThan(Maturity.ALPHA);
        assertThat(Maturity.ALPHA).isLessThan(Maturity.BETA);
        assertThat(Maturity.BETA).isLessThan(Maturity.GA);
    }
}
