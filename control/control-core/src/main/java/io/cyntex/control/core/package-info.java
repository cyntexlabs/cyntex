/**
 * Control core: the verb layer (apply / export / run / delete) and the single write entry.
 *
 * <p>Placeholder package reserving the module; the verbs and the desired-state writes are
 * added when the control layer lands. Rule R5: this module depends on core + the storage port,
 * stays framework-free (no Spring — Spring lives in rest-api, the HTTP face), and must not hold
 * a compile dependency on the runtime ring.
 */
package io.cyntex.control.core;
