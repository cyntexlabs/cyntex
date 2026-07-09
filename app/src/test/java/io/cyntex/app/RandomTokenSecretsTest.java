package io.cyntex.app;

import io.cyntex.control.core.GeneratedSecret;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The secret minter behind the machine-token port: it generates a high-entropy public id and secret,
 * stores only the SHA-256 hash of the secret, and verifies a presented secret against that hash in
 * constant time. This witnesses that a fresh secret round-trips, a wrong or tampered secret fails, and
 * two generations never collide — the real SecureRandom is exercised (no seam), asserting properties
 * rather than fixed bytes.
 */
class RandomTokenSecretsTest {

    /** base64url without padding: the alphabet is A-Z a-z 0-9 - _ only. */
    private static final Pattern BASE64_URL = Pattern.compile("[A-Za-z0-9_-]+");

    private final RandomTokenSecrets secrets = new RandomTokenSecrets();

    @Test
    void generatesHighEntropyBase64UrlPartsThatDiffer() {
        GeneratedSecret one = secrets.generate();

        assertThat(one.tokenId()).matches(BASE64_URL);
        assertThat(one.secret()).matches(BASE64_URL);
        assertThat(one.secretHash()).matches(BASE64_URL);
        assertThat(one.tokenId()).isNotEqualTo(one.secret());
        // Pin the actual entropy, not just the charset: the id is 128-bit (16 bytes) and the secret is
        // 256-bit (32 bytes) once decoded, so a regression weakening either length fails here rather
        // than sailing through on the charset check (the SHA-256 digest is a constant 32 bytes and
        // cannot witness the secret's own length).
        assertThat(Base64.getUrlDecoder().decode(one.tokenId())).hasSize(16);
        assertThat(Base64.getUrlDecoder().decode(one.secret())).hasSize(32);
        // The stored hash is not the secret, and is a 256-bit digest (32 bytes) once decoded.
        assertThat(one.secretHash()).isNotEqualTo(one.secret());
        assertThat(Base64.getUrlDecoder().decode(one.secretHash())).hasSize(32);
    }

    @Test
    void twoGenerationsNeverCollide() {
        GeneratedSecret one = secrets.generate();
        GeneratedSecret two = secrets.generate();

        assertThat(two.tokenId()).isNotEqualTo(one.tokenId());
        assertThat(two.secret()).isNotEqualTo(one.secret());
        assertThat(two.secretHash()).isNotEqualTo(one.secretHash());
    }

    @Test
    void matchesAcceptsTheGeneratedSecretAgainstItsHash() {
        GeneratedSecret generated = secrets.generate();

        assertThat(secrets.matches(generated.secret(), generated.secretHash())).isTrue();
    }

    @Test
    void matchesRejectsAWrongSecret() {
        GeneratedSecret generated = secrets.generate();

        assertThat(secrets.matches("not-the-secret", generated.secretHash())).isFalse();
        // Another freshly generated secret must not verify against this one's hash.
        assertThat(secrets.matches(secrets.generate().secret(), generated.secretHash())).isFalse();
    }

    @Test
    void matchesRejectsNullOrMalformedInput() {
        GeneratedSecret generated = secrets.generate();

        assertThat(secrets.matches(null, generated.secretHash())).isFalse();
        assertThat(secrets.matches(generated.secret(), null)).isFalse();
        // A stored hash that is not valid base64url is a corrupt record, not a match.
        assertThat(secrets.matches(generated.secret(), "not base64url!!")).isFalse();
    }
}
