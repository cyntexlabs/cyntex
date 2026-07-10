package io.cyntex.control.core;

import io.cyntex.runtime.probe.ConnectionProbe;
import io.cyntex.runtime.probe.ProbeTarget;
import io.cyntex.runtime.probe.ProbeVerdict;
import java.util.Objects;

/**
 * The control plane's one synchronous call into the runtime: testing a connection. Every other
 * control operation writes desired state the runtime converges toward, so control-core reaches the
 * runtime only through the store; a connection test cannot be modelled that way — it is a one-shot
 * request/response — so it travels this single seam, the sole compile reference control-core holds
 * into the runtime ring.
 *
 * <p>The probe is injected: the first landing supplies the reserved stub, and the real probe lands
 * with the connector plane. The HTTP projection of this verb is wired then too; here the seam exists
 * so the synchronous channel is fixed to exactly one member and the ring-dependency gate holds it
 * there.
 */
public final class ConnectionTestService {

    private final ConnectionProbe probe;

    public ConnectionTestService(ConnectionProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /** Tests the target connection through the runtime probe and returns its verdict. */
    public ProbeVerdict test(ProbeTarget target) {
        return probe.probe(target);
    }
}
