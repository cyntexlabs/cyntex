/**
 * Storage port: the persistence contract. One store surface with four concerns — the artifact
 * truth layer (canonical resources; save / get / list), the pipeline state store whose transitions
 * land only through the epoch-fencing compare-and-swap, the pipeline desired-state store (plain
 * upsert intent, the split counterpart to the state store), and the connection catalog. Pure
 * interfaces over the core ring; a database adapter implements them. Rule R2: this module depends
 * only on the core ring.
 */
package io.cyntex.spi.store;
