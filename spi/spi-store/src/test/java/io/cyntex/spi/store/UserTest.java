package io.cyntex.spi.store;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user value is a pure, fully validated record: all three fields are required and non-blank, so a
 * malformed user can never enter the store or the login flow.
 */
class UserTest {

    @Test
    void carriesItsFields() {
        User user = new User("alice", "hash-abc", "admin");

        assertThat(user.username()).isEqualTo("alice");
        assertThat(user.passwordHash()).isEqualTo("hash-abc");
        assertThat(user.role()).isEqualTo("admin");
    }

    @Test
    void rejectsABlankUsername() {
        assertThatThrownBy(() -> new User("  ", "hash-abc", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void rejectsABlankPasswordHash() {
        assertThatThrownBy(() -> new User("alice", "", "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordHash");
    }

    @Test
    void rejectsABlankRole() {
        assertThatThrownBy(() -> new User("alice", "hash-abc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");
    }
}
