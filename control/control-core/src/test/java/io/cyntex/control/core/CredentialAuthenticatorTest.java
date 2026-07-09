package io.cyntex.control.core;

import io.cyntex.spi.store.TokenRecord;
import io.cyntex.spi.store.TokenStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unified credential resolver the dispatch surface calls: it routes a presented credential to the
 * right verifier by its own shape — a machine token (the {@code cyxt_} scheme prefix) is authenticated
 * against the revocable token store, and anything else is verified as a stateless signed session token —
 * so both a {@code login} JWT and a {@code --token} machine token converge on the same
 * {@link VerifiedToken}. A bad credential is never an exception, only an absence.
 *
 * <p>The routing is asserted to be exclusive: a session token is never run through the token store, and
 * a machine token is never run through the signer — the fake signer here refuses everything, so a
 * machine token that still authenticates proves it took the token-store path, and vice versa.
 */
class CredentialAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void aMachineTokenIsRoutedToTheTokenStoreNotTheSigner() {
        TokenService tokens = new TokenService(new FakeTokenStore(), new FakeTokenSecrets(), CLOCK);
        // A signer that authenticates nothing: an accepted machine token cannot have come through it.
        CredentialAuthenticator auth = new CredentialAuthenticator(tokens, new RefusingSigner());
        String machineToken = tokens.create(Scope.WRITE); // cyxt_tok-1.sec-1

        assertThat(auth.authenticate(machineToken)).contains(new VerifiedToken("tok-1", Scope.WRITE));
    }

    @Test
    void aRevokedMachineTokenNoLongerAuthenticates() {
        TokenService tokens = new TokenService(new FakeTokenStore(), new FakeTokenSecrets(), CLOCK);
        CredentialAuthenticator auth = new CredentialAuthenticator(tokens, new RefusingSigner());
        String machineToken = tokens.create(Scope.ADMIN);

        tokens.revoke("tok-1");

        assertThat(auth.authenticate(machineToken)).isEmpty();
    }

    @Test
    void aSessionTokenIsRoutedToTheSignerNotTheTokenStore() {
        // A token store that would throw if consulted: an accepted session token cannot have touched it.
        CredentialAuthenticator auth = new CredentialAuthenticator(
                new TokenService(new ExplodingTokenStore(), new FakeTokenSecrets(), CLOCK), new FakeSigner());

        assertThat(auth.authenticate("alice|WRITE")).contains(new VerifiedToken("alice", Scope.WRITE));
    }

    @Test
    void aBadSessionTokenIsAbsentNotAnError() {
        CredentialAuthenticator auth = new CredentialAuthenticator(
                new TokenService(new FakeTokenStore(), new FakeTokenSecrets(), CLOCK), new FakeSigner());

        assertThat(auth.authenticate("not-a-valid-jwt")).isEmpty();
    }

    @Test
    void anAbsentCredentialIsEmpty() {
        CredentialAuthenticator auth = new CredentialAuthenticator(
                new TokenService(new ExplodingTokenStore(), new FakeTokenSecrets(), CLOCK), new FakeSigner());

        assertThat(auth.authenticate(null)).as("null").isEmpty();
        assertThat(auth.authenticate("")).as("empty").isEmpty();
        assertThat(auth.authenticate("   ")).as("blank").isEmpty();
    }

    /** An in-memory token store keyed by token id (mirrors TokenServiceTest's fake). */
    private static final class FakeTokenStore implements TokenStore {
        private final Map<String, TokenRecord> byId = new LinkedHashMap<>();

        @Override
        public void save(TokenRecord record) {
            byId.put(record.tokenId(), record);
        }

        @Override
        public Optional<TokenRecord> find(String tokenId) {
            return Optional.ofNullable(byId.get(tokenId));
        }

        @Override
        public void revoke(String tokenId) {
            TokenRecord existing = byId.get(tokenId);
            if (existing != null) {
                byId.put(tokenId, new TokenRecord(existing.tokenId(), existing.scope(),
                        existing.secretHash(), true, existing.createdAt()));
            }
        }

        @Override
        public List<TokenRecord> list() {
            return new ArrayList<>(byId.values());
        }
    }

    /** A token store that fails if touched, so a test can prove a code path never consulted it. */
    private static final class ExplodingTokenStore implements TokenStore {
        @Override
        public void save(TokenRecord record) {
            throw new AssertionError("the session-token path must not touch the token store");
        }

        @Override
        public Optional<TokenRecord> find(String tokenId) {
            throw new AssertionError("the session-token path must not touch the token store");
        }

        @Override
        public void revoke(String tokenId) {
            throw new AssertionError("the session-token path must not touch the token store");
        }

        @Override
        public List<TokenRecord> list() {
            throw new AssertionError("the session-token path must not touch the token store");
        }
    }

    /** A deterministic secret minter (mirrors TokenServiceTest's fake): tok-N / sec-N with a reversible hash. */
    private static final class FakeTokenSecrets implements TokenSecrets {
        private int counter;

        @Override
        public GeneratedSecret generate() {
            counter++;
            return new GeneratedSecret("tok-" + counter, "sec-" + counter, "hash:sec-" + counter);
        }

        @Override
        public boolean matches(String presentedSecret, String storedHash) {
            return storedHash.equals("hash:" + presentedSecret);
        }
    }

    /** A signer whose token is a reversible {@code subject|SCOPE} encoding, so a test can round-trip it. */
    private static final class FakeSigner implements TokenSigner {
        @Override
        public String issue(String subject, Scope scope) {
            return subject + "|" + scope.name();
        }

        @Override
        public Optional<VerifiedToken> verify(String token) {
            int bar = token.indexOf('|');
            if (bar < 0) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(token.substring(0, bar), Scope.valueOf(token.substring(bar + 1))));
        }
    }

    /** A signer that verifies nothing, so a test can prove a credential authenticated by another path. */
    private static final class RefusingSigner implements TokenSigner {
        @Override
        public String issue(String subject, Scope scope) {
            throw new AssertionError("the machine-token path must not issue a session token");
        }

        @Override
        public Optional<VerifiedToken> verify(String token) {
            return Optional.empty();
        }
    }
}
