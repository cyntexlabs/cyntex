/**
 * Sink port: the write-side delivery contract. A pure interface over the standard event envelope — a
 * writer, bound to a resolved sink configuration, accepts batches of events and writes them
 * asynchronously, applying the configured write mode and DDL policy. Rule R2: this module depends
 * only on the core ring.
 */
package io.cyntex.spi.sink;
