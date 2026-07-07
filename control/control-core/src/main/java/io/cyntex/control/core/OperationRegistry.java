package io.cyntex.control.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The single source of truth for every control operation, and the gate every dispatch passes through.
 *
 * <p>Operations are registered from an explicit list of constants supplied by the caller — there is no
 * classpath or annotation scanning, so the registry is fixed at assembly time and native-image friendly.
 * Each face's operation surface is a derivation of this data (see {@link #exposedOn(Frontend, Maturity)}),
 * which keeps the surfaces provable rather than hand-maintained.
 */
public final class OperationRegistry {

    private final Map<String, Operation> byId;

    private OperationRegistry(Map<String, Operation> byId) {
        this.byId = byId;
    }

    /** Build a registry from the given operations, rejecting a duplicate id rather than silently overwriting. */
    public static OperationRegistry of(Operation... operations) {
        return of(List.of(operations));
    }

    /** Build a registry from the given operations, rejecting a duplicate id rather than silently overwriting. */
    public static OperationRegistry of(List<Operation> operations) {
        Map<String, Operation> map = new LinkedHashMap<>();
        for (Operation op : operations) {
            Operation previous = map.putIfAbsent(op.id(), op);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate operation id: " + op.id());
            }
        }
        return new OperationRegistry(Collections.unmodifiableMap(map));
    }

    /**
     * Resolve the operation an incoming request names, rejecting any id that is not registered.
     *
     * <p>This is the dispatch gate: because the id set is fixed at registration, an unknown id is a wiring
     * bug, not user input, so it bare-crashes rather than being dressed up as a diagnosable error.
     */
    public Operation resolve(String id) {
        Operation op = byId.get(id);
        if (op == null) {
            throw new IllegalArgumentException("no operation registered for id: " + id);
        }
        return op;
    }

    /** Look up an operation without asserting it must exist. */
    public Optional<Operation> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public boolean isRegistered(String id) {
        return byId.containsKey(id);
    }

    /** All registered ids, in registration order. */
    public Set<String> ids() {
        return byId.keySet();
    }

    /** All registered operations, in registration order. */
    public List<Operation> all() {
        return List.copyOf(byId.values());
    }

    /**
     * The operation surface of a face: every operation whose stage on {@code frontend} is at or below
     * {@code ceiling}. Operations not exposed on the face are omitted. This is how a face's verb / tool
     * set is derived and asserted, rather than being listed by hand.
     */
    public List<Operation> exposedOn(Frontend frontend, Maturity ceiling) {
        Objects.requireNonNull(frontend, "frontend");
        Objects.requireNonNull(ceiling, "ceiling");
        List<Operation> out = new ArrayList<>();
        for (Operation op : byId.values()) {
            Maturity stage = op.exposure().get(frontend);
            if (stage != null && stage.compareTo(ceiling) <= 0) {
                out.add(op);
            }
        }
        return List.copyOf(out);
    }
}
