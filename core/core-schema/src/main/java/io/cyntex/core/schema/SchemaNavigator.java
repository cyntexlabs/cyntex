package io.cyntex.core.schema;

import io.cyntex.core.common.JsonReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only navigation over the bundled {@code cyntex/v1} JSON Schema: resolve a dotted field path to
 * a {@link SchemaNode}, and offer path completions. This is the blood supply for {@code explain} and
 * REPL field-path completion. It parses the bundled schema once (no reflection, native-clean) and
 * walks the document, resolving {@code $ref}, merging {@code allOf}, and reading {@code oneOf} as
 * either an enum (all-{@code const} members), the root resource union (object members discriminated
 * by a {@code kind} const), or a merged union (any other {@code oneOf}, e.g. a table ref or a
 * transform body).
 */
public final class SchemaNavigator {

    private final Map<String, Object> root;
    private final Map<String, Object> defs;

    private SchemaNavigator(Map<String, Object> root) {
        this.root = root;
        this.defs = asMap(root.get("$defs"));
    }

    /** Navigator over the bundled schema. */
    public static SchemaNavigator bundled() {
        return of(CyntexSchema.json());
    }

    /** Navigator over an explicit schema document; the testable seam. */
    static SchemaNavigator of(String schemaJson) {
        return new SchemaNavigator(asMap(JsonReader.parse(schemaJson)));
    }

    /** Resolves a dotted field path ({@code ""} = root) to its node, or empty if no such path. */
    public Optional<SchemaNode> navigate(String path) {
        Pos cur = new Pos("", root, false);
        if (path != null && !path.isBlank()) {
            for (String segment : path.split("\\.", -1)) {
                if (segment.isEmpty()) {
                    return Optional.empty();
                }
                Optional<Pos> next = descend(cur, segment);
                if (next.isEmpty()) {
                    return Optional.empty();
                }
                cur = next.get();
            }
        }
        return Optional.of(build(cur));
    }

    /**
     * Completions for a partial path: the full dotted candidates whose final segment extends what is
     * typed. {@code ""} or {@code "sou"} complete top-level kinds; {@code "source."} or
     * {@code "source.m"} complete the fields under {@code source}. Sorted for a stable display.
     */
    public List<String> complete(String partial) {
        String typed = partial == null ? "" : partial;
        int dot = typed.lastIndexOf('.');
        String parentPath = dot < 0 ? "" : typed.substring(0, dot);
        String prefix = dot < 0 ? typed : typed.substring(dot + 1);
        Optional<SchemaNode> parent = navigate(parentPath);
        if (parent.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String child : parent.get().children()) {
            if (child.startsWith(prefix)) {
                out.add(parentPath.isEmpty() ? child : parentPath + "." + child);
            }
        }
        Collections.sort(out);
        return out;
    }

    // --- navigation ---------------------------------------------------------

    /** A position during a walk: the path so far, the reference fragment as written, requiredness. */
    private record Pos(String path, Object ref, boolean required) {
    }

    /** A child found in a lookup: its reference fragment and whether the parent marks it required. */
    private record Child(Object ref, boolean required) {
    }

    private Optional<Pos> descend(Pos cur, String segment) {
        Map<String, Object> resolved = resolve(cur.ref());
        return findChild(resolved, segment)
                .map(c -> new Pos(extend(cur.path(), segment), c.ref(), c.required()));
    }

    /** Finds {@code segment} as a child of an already-resolved fragment. */
    private Optional<Child> findChild(Map<String, Object> resolved, String segment) {
        if ("array".equals(resolved.get("type"))) {
            return findChild(resolve(resolved.get("items")), segment);
        }
        List<Object> oneOf = asList(resolved.get("oneOf"));
        if (oneOf != null) {
            return switch (classify(oneOf)) {
                case ENUM -> Optional.empty();
                case KIND_UNION -> {
                    for (Object member : oneOf) {
                        Map<String, Object> m = resolve(member);
                        if (segment.equals(kindConst(m))) {
                            yield Optional.of(new Child(member, false));
                        }
                    }
                    yield Optional.empty();
                }
                case MERGE_UNION -> mergeUnionChild(oneOf, segment);
            };
        }
        Map<String, Object> properties = asMap(resolved.get("properties"));
        if (properties != null && properties.containsKey(segment)) {
            return Optional.of(new Child(properties.get(segment), requiredList(resolved).contains(segment)));
        }
        List<Object> allOf = asList(resolved.get("allOf"));
        if (allOf != null) {
            return firstChildAcross(allOf, segment);
        }
        return Optional.empty();
    }

    /** First match for {@code segment} across {@code allOf} branches, which are conjunctive: a field
     *  declared in any applied branch is present, with that branch's requiredness. */
    private Optional<Child> firstChildAcross(List<Object> branches, String segment) {
        for (Object branch : branches) {
            Optional<Child> hit = findChild(resolve(branch), segment);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    /** Resolves {@code segment} across a disjunctive {@code oneOf}: the members are mutually exclusive,
     *  so the field is required only when every member both declares and requires it (a member that
     *  lacks the field makes it conditional, hence not required). The reference fragment is taken from
     *  the first declaring member, for its type and description. */
    private Optional<Child> mergeUnionChild(List<Object> members, String segment) {
        Object ref = null;
        boolean requiredInEvery = true;
        for (Object member : members) {
            Optional<Child> hit = findChild(resolve(member), segment);
            if (hit.isEmpty()) {
                requiredInEvery = false;   // this branch lacks the field — its requiredness is conditional
                continue;
            }
            if (ref == null) {
                ref = hit.get().ref();
            }
            requiredInEvery = requiredInEvery && hit.get().required();
        }
        return ref == null ? Optional.empty() : Optional.of(new Child(ref, requiredInEvery));
    }

    // --- node assembly ------------------------------------------------------

    private SchemaNode build(Pos pos) {
        Map<String, Object> resolved = resolve(pos.ref());
        String description = description(pos.ref());
        if (description == null) {
            description = description(resolved);
        }
        return new SchemaNode(pos.path(), description, displayType(resolved), pos.required(),
                requiredList(resolved), childNames(resolved), enumValues(resolved));
    }

    private String displayType(Map<String, Object> resolved) {
        List<Object> oneOf = asList(resolved.get("oneOf"));
        if (oneOf != null) {
            return classify(oneOf) == OneOf.ENUM ? "enum" : "object";
        }
        if (resolved.get("type") instanceof String t) {
            return t;
        }
        if (resolved.containsKey("const")) {
            return "const";
        }
        return "object";
    }

    private List<String> childNames(Map<String, Object> resolved) {
        if ("array".equals(resolved.get("type"))) {
            return childNames(resolve(resolved.get("items")));
        }
        List<Object> oneOf = asList(resolved.get("oneOf"));
        if (oneOf != null) {
            return switch (classify(oneOf)) {
                case ENUM -> List.of();
                case KIND_UNION -> oneOf.stream().map(m -> kindConst(resolve(m))).toList();
                case MERGE_UNION -> unionChildren(oneOf);
            };
        }
        Set<String> names = new LinkedHashSet<>();
        Map<String, Object> properties = asMap(resolved.get("properties"));
        if (properties != null) {
            names.addAll(properties.keySet());
        }
        List<Object> allOf = asList(resolved.get("allOf"));
        if (allOf != null) {
            for (Object branch : allOf) {
                names.addAll(childNames(resolve(branch)));
            }
        }
        return new ArrayList<>(names);
    }

    private List<String> unionChildren(List<Object> members) {
        Set<String> names = new LinkedHashSet<>();
        for (Object member : members) {
            names.addAll(childNames(resolve(member)));
        }
        return new ArrayList<>(names);
    }

    private List<SchemaNode.EnumValue> enumValues(Map<String, Object> resolved) {
        List<Object> oneOf = asList(resolved.get("oneOf"));
        if (oneOf == null || classify(oneOf) != OneOf.ENUM) {
            return List.of();
        }
        List<SchemaNode.EnumValue> values = new ArrayList<>();
        for (Object member : oneOf) {
            Map<String, Object> m = resolve(member);
            values.add(new SchemaNode.EnumValue(String.valueOf(m.get("const")), description(m)));
        }
        return values;
    }

    // --- oneOf classification ----------------------------------------------

    private enum OneOf {ENUM, KIND_UNION, MERGE_UNION}

    private OneOf classify(List<Object> members) {
        boolean allConst = true;
        boolean allKind = true;
        for (Object member : members) {
            Map<String, Object> m = resolve(member);
            if (!m.containsKey("const")) {
                allConst = false;
            }
            if (kindConst(m) == null) {
                allKind = false;
            }
        }
        if (allConst) {
            return OneOf.ENUM;
        }
        return allKind ? OneOf.KIND_UNION : OneOf.MERGE_UNION;
    }

    /** The {@code kind} discriminator constant of an object def, or null if it has none. */
    private String kindConst(Map<String, Object> resolved) {
        Map<String, Object> properties = asMap(resolved.get("properties"));
        if (properties == null) {
            return null;
        }
        Map<String, Object> kind = asMap(properties.get("kind"));
        if (kind == null || !(kind.get("const") instanceof String c)) {
            return null;
        }
        return c;
    }

    // --- low-level helpers --------------------------------------------------

    /** Follows a {@code $ref} chain to its target def; returns non-ref maps unchanged. */
    private Map<String, Object> resolve(Object fragment) {
        Map<String, Object> m = asMap(fragment);
        while (m != null && m.get("$ref") instanceof String ref) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            Map<String, Object> target = asMap(defs.get(name));
            if (target == null) {
                throw new IllegalStateException("schema $ref to missing def: " + ref);
            }
            m = target;
        }
        return m;
    }

    private static String description(Object fragment) {
        Map<String, Object> m = asMap(fragment);
        return m != null && m.get("description") instanceof String d ? d : null;
    }

    private static List<String> requiredList(Map<String, Object> resolved) {
        List<Object> required = asList(resolved.get("required"));
        if (required == null) {
            return List.of();
        }
        return required.stream().map(String::valueOf).toList();
    }

    private static String extend(String path, String segment) {
        return path.isEmpty() ? segment : path + "." + segment;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }
}
