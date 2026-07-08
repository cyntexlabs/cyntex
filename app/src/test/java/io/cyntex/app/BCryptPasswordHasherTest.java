package io.cyntex.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bcrypt hasher: a password verifies against its own hash, a wrong password does not, and hashing
 * the same password twice yields different strings — proof a fresh random salt is embedded each time,
 * so equal passwords never share a stored hash.
 */
class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

    @Test
    void aPasswordMatchesItsOwnHash() {
        String stored = hasher.hash("correct horse");

        assertThat(hasher.matches("correct horse", stored)).isTrue();
    }

    @Test
    void aWrongPasswordDoesNotMatch() {
        String stored = hasher.hash("correct horse");

        assertThat(hasher.matches("battery staple", stored)).isFalse();
    }

    @Test
    void hashingTheSamePasswordTwiceYieldsDifferentSaltedHashes() {
        String first = hasher.hash("correct horse");
        String second = hasher.hash("correct horse");

        assertThat(first).isNotEqualTo(second);
        // Both still verify: the salt is embedded in each string, not stored separately.
        assertThat(hasher.matches("correct horse", first)).isTrue();
        assertThat(hasher.matches("correct horse", second)).isTrue();
    }
}
