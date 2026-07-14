/**
 * The probe seam: the synchronous control-to-runtime whitelist. Control writes desired state and the
 * runtime watches and converges, so the two rings otherwise hold no reference to each other and
 * decouple entirely through the store. The exceptions are the one-shot request/response calls that
 * cannot be modelled as desired state a watcher converges toward — testing a connection and
 * discovering a schema — so they travel this narrow synchronous seam. The whitelist is a closed set
 * of exactly these two probes; widening it is a deliberate change to the seam, guarded by the
 * ring-dependency gate.
 */
package io.cyntex.runtime.probe;
