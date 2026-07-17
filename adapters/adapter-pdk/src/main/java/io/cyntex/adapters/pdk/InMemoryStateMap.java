package io.cyntex.adapters.pdk;

import io.tapdata.entity.utils.cache.KVMap;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-connector, in-memory {@link KVMap} for the driving context's state. The PDK hands a connector a
 * key-value scratch map through its context, read and written during init, discovery and the read / write
 * drive; without one the connector meets a null the moment it touches it. At L1 this is single-node and
 * lives only as long as the connector is open — a plain concurrent map, dropped with the connector.
 * Durable state across restarts is not L1's model (a restart re-snapshots), so nothing here persists.
 */
final class InMemoryStateMap implements KVMap<Object> {

    private final ConcurrentHashMap<String, Object> entries = new ConcurrentHashMap<>();

    @Override
    public void init(String mapKey, Class<Object> valueClass) {
        // The map is already live. init names a map and its value type for a backing store to provision;
        // an in-memory map has neither to set up.
    }

    @Override
    public void put(String key, Object value) {
        entries.put(key, value);
    }

    @Override
    public Object putIfAbsent(String key, Object value) {
        return entries.putIfAbsent(key, value);
    }

    @Override
    public Object get(String key) {
        return entries.get(key);
    }

    @Override
    public Object remove(String key) {
        return entries.remove(key);
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public void reset() {
        entries.clear();
    }
}
