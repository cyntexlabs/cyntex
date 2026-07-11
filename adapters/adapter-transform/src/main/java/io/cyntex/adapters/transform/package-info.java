/**
 * The stateless row-transform adapter: evaluates a {@code filter} (CEL predicate) and a {@code map}
 * (field projection) into the transform port the engine runs. It reuses the one CEL compiler
 * environment the validate layer type-checks against, so a validated expression is the one that
 * evaluates. The port is engine-free (no Hazelcast type); the app assembly root wraps it in the Jet
 * supplier that carries it to a member.
 */
package io.cyntex.adapters.transform;
