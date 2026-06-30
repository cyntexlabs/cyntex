/**
 * The service assembly root. Brings the whole platform up in a single process and is the one place
 * permitted to depend on the adapters ring (rule R7): the adapter bridges are bound into the runtime
 * here under the conditional {@code --role} wiring. At L1 the only supported role is {@code all}.
 */
package io.cyntex.app;
