/**
 * The standard event envelope, as pure data with no third-party dependency.
 *
 * <p>{@link io.cyntex.core.event.Envelope} is one change event as every transform sees it — the
 * common currency of the capture, transform and sink ports. Its {@link io.cyntex.core.event.Op} is
 * a closed set of five change kinds. Mapping a connector's native change event onto this shape
 * lives in an adapter; this package only defines the in-memory shape and its invariants, so the
 * port contracts single-source the event currency without depending on the authoring model.
 */
package io.cyntex.core.event;
