package io.cyntex.runtime.probe;

/**
 * The one synchronous call the control plane is permitted to make into the runtime: a connection
 * probe. Every other control operation writes desired state the runtime converges toward, so the
 * two rings decouple through the store and hold no reference to each other; testing a connection is
 * the single exception, because it is a one-shot request/response that cannot be expressed as
 * desired state. This interface is that narrow seam.
 *
 * <p>The whitelist is a closed set of exactly one member (this probe). A preview, a schema
 * discovery, any further synchronous control-to-runtime call is a deliberate widening of the seam
 * guarded by the ring-dependency gate, not something a later slice adds in passing.
 *
 * <p>Predeclared contract: the first landing wires the {@link UnavailableConnectionProbe stub},
 * which reports {@link ProbeVerdict.Outcome#NOT_IMPLEMENTED}. The real probe — opening the connector
 * through the engine and reporting whether the target is reachable — lands with the connector plane
 * in a later slice; this seam gives it a compile-time contract to implement.
 */
public interface ConnectionProbe {

    /** Probes the target connection synchronously and reports the verdict. */
    ProbeVerdict probe(ProbeTarget target);
}
