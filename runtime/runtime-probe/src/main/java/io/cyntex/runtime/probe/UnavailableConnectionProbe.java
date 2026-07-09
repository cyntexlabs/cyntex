package io.cyntex.runtime.probe;

/**
 * The first-landing connection probe: a stub that always reports
 * {@link ProbeVerdict.Outcome#NOT_IMPLEMENTED}. It fills the seam so the synchronous
 * control-to-runtime channel is wired end to end while the real probe — opening the connector
 * through the engine — is still to land. A later slice replaces it with the engine-backed
 * implementation.
 */
public final class UnavailableConnectionProbe implements ConnectionProbe {

    @Override
    public ProbeVerdict probe(ProbeTarget target) {
        return ProbeVerdict.notImplemented("connection testing is not available in this build yet");
    }
}
