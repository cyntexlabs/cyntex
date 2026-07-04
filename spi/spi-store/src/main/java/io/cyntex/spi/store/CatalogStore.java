package io.cyntex.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * The connection catalog store: persists the connection / connector-instance configurations a
 * workspace has registered. A pure interface (rule R2).
 *
 * <p>The identity is the connection's id. {@link #save} upserts a connection by that id;
 * {@link #get} returns the stored connection for an id, or empty when none is stored; {@link #list}
 * returns every stored connection.
 */
public interface CatalogStore {

    /** Upserts the connection by its id. */
    void save(ConnectionConfig connection);

    /** Returns the stored connection for the id, or empty if none is stored. */
    Optional<ConnectionConfig> get(String id);

    /** Lists every stored connection. */
    List<ConnectionConfig> list();
}
