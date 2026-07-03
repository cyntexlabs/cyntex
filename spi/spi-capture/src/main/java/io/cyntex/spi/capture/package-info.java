/**
 * Source capture port: the read-side extraction contract. A pure interface over the standard event
 * envelope — bounded snapshot reads, an unbounded CDC stream, a connection test and schema
 * discovery. Rule R2: this module depends only on the core ring.
 */
package io.cyntex.spi.capture;
