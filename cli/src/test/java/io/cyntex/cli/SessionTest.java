package io.cyntex.cli;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The REPL's mutable connection state: it starts offline, {@code connect} records the ordered seed
 * list plus the landing node that answered the probe, and {@code disconnect} clears it back. It holds
 * a transport target only — no credential — since connecting is decoupled from authenticating.
 */
class SessionTest {

    @Test
    void aFreshSessionIsOffline() {
        Session session = new Session();
        assertThat(session.isConnected()).isFalse();
        assertThat(session.landingNode()).isNull();
        assertThat(session.seeds()).isEmpty();
    }

    @Test
    void connectRecordsTheSeedsAndLandingNodeAndFlipsConnected() {
        Session session = new Session();
        List<URI> seeds = List.of(URI.create("http://node1:7900"), URI.create("http://node2:7900"));
        session.connect(seeds, URI.create("http://node2:7900"));
        assertThat(session.isConnected()).isTrue();
        assertThat(session.landingNode()).isEqualTo(URI.create("http://node2:7900"));
        assertThat(session.seeds()).containsExactlyElementsOf(seeds);
    }

    @Test
    void disconnectClearsTheSessionBackToOffline() {
        Session session = new Session();
        session.connect(List.of(URI.create("http://node1:7900")), URI.create("http://node1:7900"));
        session.disconnect();
        assertThat(session.isConnected()).isFalse();
        assertThat(session.landingNode()).isNull();
        assertThat(session.seeds()).isEmpty();
    }

    @Test
    void theSeedListIsCopiedDefensivelyOnConnect() {
        Session session = new Session();
        List<URI> seeds = new ArrayList<>(List.of(URI.create("http://node1:7900")));
        session.connect(seeds, URI.create("http://node1:7900"));
        seeds.add(URI.create("http://intruder:9999"));
        assertThat(session.seeds()).containsExactly(URI.create("http://node1:7900"));
    }

    @Test
    void theSeedsAccessorIsUnmodifiable() {
        Session session = new Session();
        session.connect(List.of(URI.create("http://node1:7900")), URI.create("http://node1:7900"));
        assertThatThrownBy(() -> session.seeds().add(URI.create("http://x:1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theSessionCarriesNoCredentialFieldYet() {
        // connecting establishes a transport target, never authentication; no credential state may
        // leak into this slice's session shape (username / password / token / credential)
        for (Field f : Session.class.getDeclaredFields()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            assertThat(name)
                    .doesNotContain("credential")
                    .doesNotContain("token")
                    .doesNotContain("password")
                    .doesNotContain("user");
        }
    }
}
