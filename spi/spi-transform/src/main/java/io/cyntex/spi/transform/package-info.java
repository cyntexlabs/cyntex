/**
 * Transform port: the in-pipeline transform contract. A pure interface over the standard event
 * envelope — the row-level seam ({@link io.cyntex.spi.transform.TransformPort}) that a stateless
 * node maps one event through into zero-or-more events, plus the closed set of transform node kinds
 * ({@link io.cyntex.spi.transform.NodeType}). The stateful (nest / join) and fan-in (union) node
 * execution contracts land with the execution engine; this module predeclares the seam. Rule R2:
 * this module depends only on the core ring.
 */
package io.cyntex.spi.transform;
