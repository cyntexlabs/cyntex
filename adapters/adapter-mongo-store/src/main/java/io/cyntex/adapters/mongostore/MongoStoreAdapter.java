package io.cyntex.adapters.mongostore;

/**
 * MongoDB store adapter entry point.
 *
 * <p>Placeholder reserving the module; not instantiable and carries no behavior yet. The
 * real compare-and-swap transaction over a Mongo replica-set is implemented here later. Rule
 * R3: this is the only module permitted to depend on the Mongo driver.
 */
public final class MongoStoreAdapter {

    private MongoStoreAdapter() {
    }
}
