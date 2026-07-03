/**
 * Pipeline lifecycle contracts, as pure data and pure functions with no third-party dependency.
 *
 * <p>Two halves: the intent state machine ({@link io.cyntex.core.lifecycle.PipelineState} +
 * {@link io.cyntex.core.lifecycle.LifecycleVerb} + the {@link io.cyntex.core.lifecycle.LifecycleMachine}
 * transition table), and the persistence contract that prevents two owners writing at once
 * ({@link io.cyntex.core.lifecycle.CheckpointDoc} swapped by compare-and-swap on a monotonic fencing
 * epoch, {@link io.cyntex.core.lifecycle.EpochCas}). The real store transaction that executes the CAS
 * lives in an adapter; this package only defines the contract so it is single-sourced and testable
 * without a database.
 */
package io.cyntex.core.lifecycle;
