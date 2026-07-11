package io.cyntex.runtime.probe;

import io.cyntex.spi.store.ConnectionConfig;
import io.cyntex.spi.store.ConnectionTestResult;

/**
 * The one synchronous call the control plane is permitted to make into the runtime: a connection test.
 * Every other control operation writes desired state the runtime converges toward, so the two rings
 * decouple through the store and hold no reference to each other; testing a connection is the single
 * exception, because it is a one-shot request/response that cannot be expressed as desired state. This
 * interface is that narrow seam.
 *
 * <p>The whitelist is a closed set of exactly one member (this probe). A preview, a schema discovery,
 * any further synchronous control-to-runtime call is a deliberate widening of the seam guarded by the
 * ring-dependency gate, not something a later slice adds in passing.
 *
 * <p>The probe drives the target connector's own connection test and reports the normalized
 * {@link ConnectionTestResult}: the overall outcome plus the per-item checks the connector reported.
 * In the first landing the control and runtime rings share one process and this is a direct in-process
 * call; when the roles split, this seam is where the call becomes a request across instances — which is
 * why the result it returns carries no connector-framework types and is addressed by a stored
 * {@link ConnectionConfig} rather than a live connector handle.
 */
public interface ConnectionProbe {

    /** Probes the target connection synchronously and reports the normalized result. */
    ConnectionTestResult probe(ConnectionConfig config);
}
