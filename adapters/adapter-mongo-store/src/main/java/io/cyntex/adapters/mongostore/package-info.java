/**
 * MongoDB implementation of the storage port.
 *
 * <p>This is the only place allowed to depend on the Mongo driver (rule R3). It carries the
 * connection substrate and the three storage-port sub-stores — the artifact truth layer, the
 * epoch-fencing CAS state store, and the connection catalog — plus the single point that translates
 * driver failures into io-domain coded diagnostics, so no driver type escapes the package.
 */
package io.cyntex.adapters.mongostore;
