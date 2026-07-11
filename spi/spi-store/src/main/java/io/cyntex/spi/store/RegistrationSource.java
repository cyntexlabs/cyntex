package io.cyntex.spi.store;

/**
 * How a connector came to be registered: swept from the release seed directory at startup, or applied
 * at runtime through an explicit register operation. Both go through the same content-hash idempotent
 * register path; the source is recorded only to say where a registration originated.
 */
public enum RegistrationSource {

    /** Swept from the release seed directory during startup. */
    SEED,

    /** Applied at runtime through an explicit register operation. */
    REGISTER
}
