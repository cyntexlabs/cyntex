package io.cyntex.tools.catalog.assembler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Re-walks a parsed spec's connection form to surface the two normalizer degradations the catalog
 * cannot otherwise show, so the ingest report can list them rather than swallow them: a present but
 * unrecognized Formily {@code type} token (which falls to a string input) and a {@code ${key}} label
 * ref that does not resolve in the default-locale bundle (which falls back to the raw key). Mirrors
 * the normalizer's container/hidden traversal so it sees the same fields.
 */
final class SpecAuditor {

    private static final String DEFAULT_LOCALE = "en_US";

    private SpecAuditor() {
    }

    static Findings audit(Map<String, Object> spec) {
        Map<String, Object> configOptions = asMap(spec.get("configOptions"));
        Map<String, Object> connectionProps =
                asMap(asMap(configOptions.get("connection")).get("properties"));

        Map<String, Object> messages = asMap(spec.get("messages"));
        String defaultLocale = str(messages.get("default"));
        Map<String, Object> bundle = asMap(messages.get(defaultLocale != null ? defaultLocale : DEFAULT_LOCALE));

        List<String> unknownTypeFields = new ArrayList<>();
        List<String> unresolvedLabelRefs = new ArrayList<>();
        walk(connectionProps, bundle, unknownTypeFields, unresolvedLabelRefs);
        return new Findings(unknownTypeFields, unresolvedLabelRefs);
    }

    private static void walk(Map<String, Object> props, Map<String, Object> bundle,
                             List<String> unknownTypeFields, List<String> unresolvedLabelRefs) {
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> def = asMap(entry.getValue());
            if (isContainer(def)) {
                walk(asMap(def.get("properties")), bundle, unknownTypeFields, unresolvedLabelRefs);
                continue;
            }
            if ("hidden".equals(str(def.get("x-display")))) {
                continue;
            }
            String type = str(def.get("type"));
            if (type != null && !isRecognizedType(type)) {
                unknownTypeFields.add(entry.getKey());
            }
            checkLabel(str(def.get("title")), bundle, unresolvedLabelRefs);
            for (Object option : asList(def.get("enum"))) {
                checkLabel(str(asMap(option).get("label")), bundle, unresolvedLabelRefs);
            }
        }
    }

    private static void checkLabel(String title, Map<String, Object> bundle, List<String> out) {
        if (title == null || !(title.startsWith("${") && title.endsWith("}"))) {
            return;
        }
        String key = title.substring(2, title.length() - 1);
        if (!(bundle.get(key) instanceof String)) {
            out.add(key);
        }
    }

    private static boolean isContainer(Map<String, Object> def) {
        String type = str(def.get("type"));
        if (type == null) {
            return false;
        }
        String t = type.toLowerCase(Locale.ROOT);
        return "void".equals(t) || ("object".equals(t) && def.get("properties") != null);
    }

    private static boolean isRecognizedType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string", "number", "integer", "int", "boolean", "array", "object", "void" -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static String str(Object value) {
        return value instanceof String s ? s : null;
    }

    record Findings(List<String> unknownTypeFields, List<String> unresolvedLabelRefs) {
    }
}
