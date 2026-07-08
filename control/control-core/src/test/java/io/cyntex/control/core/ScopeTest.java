package io.cyntex.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScopeTest {

    // The declaration order is the privilege order READ < WRITE < ADMIN, and permits() is the single
    // place the credential-scope check reads that ordering from.
    @Test
    void privilegeOrderIsPinned() {
        assertThat(Scope.values()).containsExactly(Scope.READ, Scope.WRITE, Scope.ADMIN);
        assertThat(Scope.READ).isLessThan(Scope.WRITE);
        assertThat(Scope.WRITE).isLessThan(Scope.ADMIN);
    }

    @Test
    void adminPermitsEveryGrade() {
        assertThat(Scope.ADMIN.permits(Scope.READ)).isTrue();
        assertThat(Scope.ADMIN.permits(Scope.WRITE)).isTrue();
        assertThat(Scope.ADMIN.permits(Scope.ADMIN)).isTrue();
    }

    @Test
    void writePermitsWriteAndReadButNotAdmin() {
        assertThat(Scope.WRITE.permits(Scope.READ)).isTrue();
        assertThat(Scope.WRITE.permits(Scope.WRITE)).isTrue();
        assertThat(Scope.WRITE.permits(Scope.ADMIN)).isFalse();
    }

    @Test
    void readPermitsOnlyRead() {
        assertThat(Scope.READ.permits(Scope.READ)).isTrue();
        assertThat(Scope.READ.permits(Scope.WRITE)).isFalse();
        assertThat(Scope.READ.permits(Scope.ADMIN)).isFalse();
    }

    @Test
    void permitsRejectsANullRequiredGrade() {
        assertThatThrownBy(() -> Scope.WRITE.permits(null))
                .isInstanceOf(NullPointerException.class);
    }
}
