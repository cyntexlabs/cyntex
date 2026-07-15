package io.cyntex.e2e;

/**
 * Addresses one table on one endpoint, spelled {@code <resourceId>.<table>}. The resource id is the
 * product DSL's own identity currency, so an endpoint is named here exactly as the {@code .cyn.yml}
 * that declares it names itself. Naming endpoints by connector instead would collide the moment a
 * specification reads and writes through the same connector.
 */
public record TableAlias(String resourceId, String table) {

    @Override
    public String toString() {
        return resourceId + "." + table;
    }
}
