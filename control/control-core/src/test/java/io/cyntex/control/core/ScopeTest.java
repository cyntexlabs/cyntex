package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScopeTest {

    // The declaration order is the privilege order READ < WRITE < ADMIN. No code grades credentials by
    // it yet, but the later credential-scope check depends on this ordering, so pin it now.
    @Test
    void privilegeOrderIsPinned() {
        assertThat(Scope.values()).containsExactly(Scope.READ, Scope.WRITE, Scope.ADMIN);
        assertThat(Scope.READ).isLessThan(Scope.WRITE);
        assertThat(Scope.WRITE).isLessThan(Scope.ADMIN);
    }
}
