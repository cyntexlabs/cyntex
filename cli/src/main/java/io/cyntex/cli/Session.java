package io.cyntex.cli;

import java.net.URI;
import java.util.List;

/**
 * The REPL's mutable connection state, carried across read-loop iterations. A session is either
 * offline or connected: connecting records the ordered seed list that was tried and the landing node
 * (the base URL that answered the reachability probe). It holds a transport target only — no
 * credential — because connecting is decoupled from authenticating; credential and cluster state
 * belong to a later slice.
 */
final class Session {

    private List<URI> seeds = List.of();
    private URI landingNode;
    private boolean connected;

    /** Whether the session currently targets a reachable server. */
    boolean isConnected() {
        return connected;
    }

    /** The base URL that answered the probe, or {@code null} while offline. */
    URI landingNode() {
        return landingNode;
    }

    /** The ordered seed list used to connect (unmodifiable); empty while offline. */
    List<URI> seeds() {
        return seeds;
    }

    /** Records a successful connection: the seeds tried and the landing node that answered. */
    void connect(List<URI> seeds, URI landingNode) {
        this.seeds = List.copyOf(seeds);
        this.landingNode = landingNode;
        this.connected = true;
    }

    /** Clears the session back to offline. */
    void disconnect() {
        this.seeds = List.of();
        this.landingNode = null;
        this.connected = false;
    }
}
