/**
 * The connection-probe seam: the one synchronous control-to-runtime channel. Control writes desired
 * state and the runtime watches and converges, so the two rings otherwise hold no reference to each
 * other and decouple entirely through the store. Testing a connection is the single exception —
 * a one-shot request/response that cannot be modelled as desired state a watcher converges toward —
 * so it travels this narrow synchronous seam. The whitelist is a closed set of exactly this one
 * probe; widening it is a deliberate change to the seam, guarded by the ring-dependency gate.
 */
package io.cyntex.runtime.probe;
