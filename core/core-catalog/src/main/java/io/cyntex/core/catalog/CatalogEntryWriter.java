package io.cyntex.core.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes a {@link ConnectorCatalogEntry} to the neutral tree the catalog product format defines —
 * the producer dual of {@link CatalogEntryReader}. Every key is emitted in a fixed order with nulls
 * explicit, so the output is a fully specified, byte-lockable shape. Enum-valued fields are written
 * with each enum's {@code yaml()} code. The bundled-catalog build tool and the runtime register path
 * share this one producer so both emit the same format the reader consumes.
 */
public final class CatalogEntryWriter {

    private CatalogEntryWriter() {
    }

    public static Map<String, Object> toTree(ConnectorCatalogEntry entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", entry.id());
        m.put("name", entry.name());
        m.put("displayName", entry.displayName());
        m.put("icon", entry.icon());
        m.put("group", entry.group().yaml());
        m.put("modes", modes(entry));
        m.put("discovery", entry.discovery().yaml());
        m.put("sink", sink(entry.sink()));
        m.put("pushOut", entry.pushOut());
        m.put("config", config(entry.config()));
        m.put("provenance", provenance(entry.provenance()));
        return m;
    }

    private static List<Object> modes(ConnectorCatalogEntry entry) {
        List<Object> modes = new ArrayList<>();
        entry.modes().forEach(mode -> modes.add(mode.yaml()));
        return modes;
    }

    private static Map<String, Object> sink(SinkCapability sink) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capable", sink.capable());
        List<Object> semantics = new ArrayList<>();
        sink.writeSemantics().forEach(mode -> semantics.add(mode.yaml()));
        m.put("writeSemantics", semantics);
        return m;
    }

    private static List<Object> config(List<ConfigField> fields) {
        List<Object> list = new ArrayList<>();
        for (ConfigField field : fields) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", field.name());
            m.put("type", field.type().yaml());
            m.put("label", new LinkedHashMap<Object, Object>(field.label()));
            m.put("required", field.required());
            m.put("default", field.defaultValue());
            m.put("secret", field.secret());
            m.put("options", options(field.options()));
            m.put("visibleWhen", visibleWhen(field.visibleWhen()));
            list.add(m);
        }
        return list;
    }

    private static List<Object> options(List<EnumOption> options) {
        List<Object> list = new ArrayList<>();
        for (EnumOption option : options) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", option.value());
            m.put("label", new LinkedHashMap<Object, Object>(option.label()));
            list.add(m);
        }
        return list;
    }

    private static Object visibleWhen(VisibleWhen visibleWhen) {
        if (visibleWhen == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("controllingField", visibleWhen.controllingField());
        m.put("equalsAnyOf", new ArrayList<Object>(visibleWhen.equalsAnyOf()));
        return m;
    }

    private static Map<String, Object> provenance(Provenance provenance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connectorRepoSha", provenance.connectorRepoSha());
        m.put("specPath", provenance.specPath());
        m.put("specContentHash", provenance.specContentHash());
        m.put("pdkApiVersion", provenance.pdkApiVersion());
        m.put("requiredLevel", provenance.requiredLevel());
        Map<String, Object> modeSource = new LinkedHashMap<>();
        provenance.modeSource().forEach((mode, source) -> modeSource.put(mode.yaml(), source.yaml()));
        m.put("modeSource", modeSource);
        return m;
    }
}
