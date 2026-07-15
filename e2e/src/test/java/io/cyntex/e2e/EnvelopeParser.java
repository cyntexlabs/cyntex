package io.cyntex.e2e;

import io.cyntex.core.lifecycle.LifecycleVerb;
import io.cyntex.core.lifecycle.PipelineState;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a test specification. Unknown keys, verbs and matcher words are rejected rather than
 * ignored: a specification that silently drops a step an author wrote would pass while testing
 * something other than what it says.
 */
public final class EnvelopeParser {

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("name", "tier", "setup", "pipeline", "seed", "steps");
    private static final Set<String> SETUP_KEYS = Set.of("connectors", "apply", "discover");

    private EnvelopeParser() {}

    public static Envelope parse(String yaml) {
        Map<String, Object> root = loadMapping(yaml);
        rejectUnknownKeys(root.keySet(), TOP_LEVEL_KEYS, "envelope");
        return new Envelope(
                requiredString(root, "name"),
                tier(requiredString(root, "tier")),
                setup(root.get("setup")),
                requiredString(root, "pipeline"),
                seed(root.get("seed")),
                steps(root.get("steps")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMapping(String yaml) {
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (YAMLException e) {
            throw new EnvelopeException("envelope is not well-formed YAML: " + e.getMessage(), e);
        }
        if (!(loaded instanceof Map<?, ?> mapping)) {
            throw new EnvelopeException("envelope must be a mapping, found: " + describe(loaded));
        }
        return (Map<String, Object>) mapping;
    }

    private static Setup setup(Object node) {
        if (node == null) {
            return Setup.NONE;
        }
        Map<String, Object> mapping = mapping(node, "setup");
        rejectUnknownKeys(mapping.keySet(), SETUP_KEYS, "setup");
        return new Setup(
                stringList(mapping.get("connectors"), "setup.connectors"),
                stringList(mapping.get("apply"), "setup.apply"),
                stringList(mapping.get("discover"), "setup.discover"));
    }

    private static List<Seed> seed(Object node) {
        if (node == null) {
            return List.of();
        }
        List<Seed> seeds = new ArrayList<>();
        mapping(node, "seed")
                .forEach(
                        (alias, spec) -> {
                            Map<String, Object> rows = mapping(spec, "seed." + alias);
                            rejectUnknownKeys(rows.keySet(), Set.of("rows"), "seed." + alias);
                            seeds.add(new Seed(alias(alias), longValue(rows.get("rows"), "seed." + alias + ".rows")));
                        });
        return seeds;
    }

    private static List<Step> steps(Object node) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> sequence)) {
            throw new EnvelopeException("steps must be a sequence, found: " + describe(node));
        }
        List<Step> steps = new ArrayList<>();
        for (Object element : sequence) {
            steps.add(step(element));
        }
        return steps;
    }

    private static Step step(Object element) {
        if (element instanceof String verb) {
            return new Step.Lifecycle(lifecycleVerb(verb));
        }
        Map<String, Object> mapping = mapping(element, "step");
        if (mapping.size() != 1) {
            throw new EnvelopeException(
                    "a step carries exactly one verb, found: " + mapping.keySet());
        }
        Map.Entry<String, Object> only = mapping.entrySet().iterator().next();
        return switch (only.getKey()) {
            case "await" -> new Step.Await(matcher(only.getValue()));
            case "assert" -> new Step.Assertion(matcher(only.getValue()));
            case "cdc" -> cdc(only.getValue());
            default -> throw new EnvelopeException(
                    "unknown step verb: " + only.getKey() + "; known verbs are run, pause, resume, stop,"
                            + " cdc, await, assert");
        };
    }

    private static LifecycleVerb lifecycleVerb(String verb) {
        // The specification says "run" where the product verb is "start"; the rest map by name.
        return switch (verb) {
            case "run" -> LifecycleVerb.START;
            case "pause" -> LifecycleVerb.PAUSE;
            case "resume" -> LifecycleVerb.RESUME;
            case "stop" -> LifecycleVerb.STOP;
            default -> throw new EnvelopeException(
                    "unknown step verb: " + verb + "; known verbs are run, pause, resume, stop, cdc,"
                            + " await, assert");
        };
    }

    private static Step.Cdc cdc(Object node) {
        Map<String, Object> mapping = mapping(node, "cdc");
        if (mapping.size() != 1) {
            throw new EnvelopeException("a cdc step names exactly one table, found: " + mapping.keySet());
        }
        Map.Entry<String, Object> only = mapping.entrySet().iterator().next();
        String spec = string(only.getValue(), "cdc." + only.getKey());
        String[] parts = spec.trim().split("\\s+");
        if (parts.length != 2) {
            throw new EnvelopeException(
                    "a cdc change reads '<op> <rows>', found: " + spec);
        }
        return new Step.Cdc(alias(only.getKey()), cdcOp(parts[0]), intValue(parts[1], "cdc rows"));
    }

    private static CdcOp cdcOp(String op) {
        try {
            return CdcOp.valueOf(op.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EnvelopeException(
                    "unknown cdc operation: " + op + "; known operations are insert, update, delete");
        }
    }

    private static Matcher matcher(Object node) {
        Map<String, Object> mapping = mapping(node, "matcher");
        if (mapping.size() != 1) {
            throw new EnvelopeException("a matcher carries exactly one word, found: " + mapping.keySet());
        }
        Map.Entry<String, Object> only = mapping.entrySet().iterator().next();
        return switch (only.getKey()) {
            case "count" -> count(only.getValue());
            case "state" -> state(only.getValue());
            default -> throw new EnvelopeException(
                    "unknown matcher: " + only.getKey() + "; known matchers are count, state");
        };
    }

    private static Matcher count(Object node) {
        Map<TableAlias, Long> expected = new LinkedHashMap<>();
        mapping(node, "count")
                .forEach((alias, rows) -> expected.put(alias(alias), longValue(rows, "count." + alias)));
        return new Matcher.Count(expected);
    }

    private static Matcher state(Object node) {
        Map<String, PipelineState> expected = new LinkedHashMap<>();
        mapping(node, "state").forEach((pipelineId, state) -> expected.put(pipelineId, pipelineState(state)));
        return new Matcher.State(expected);
    }

    private static PipelineState pipelineState(Object node) {
        String state = string(node, "state");
        try {
            return PipelineState.valueOf(state.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EnvelopeException("unknown pipeline state: " + state);
        }
    }

    private static TableAlias alias(String alias) {
        int dot = alias.indexOf('.');
        if (dot <= 0 || dot != alias.lastIndexOf('.') || dot == alias.length() - 1) {
            throw new EnvelopeException(
                    "a table alias reads '<resourceId>.<table>', found: " + alias);
        }
        return new TableAlias(alias.substring(0, dot), alias.substring(dot + 1));
    }

    private static Tier tier(String tier) {
        try {
            return Tier.valueOf(tier.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EnvelopeException("unknown tier: " + tier + "; known tiers are smoke, full, perf");
        }
    }

    private static void rejectUnknownKeys(Set<String> present, Set<String> known, String where) {
        for (String key : present) {
            if (!known.contains(key)) {
                throw new EnvelopeException("unknown " + where + " key: " + key + "; known keys are " + known);
            }
        }
    }

    private static String requiredString(Map<String, Object> mapping, String key) {
        Object value = mapping.get(key);
        if (value == null) {
            throw new EnvelopeException("envelope is missing the required key: " + key);
        }
        return string(value, key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object node, String where) {
        if (!(node instanceof Map<?, ?> mapping)) {
            throw new EnvelopeException(where + " must be a mapping, found: " + describe(node));
        }
        return (Map<String, Object>) mapping;
    }

    private static List<String> stringList(Object node, String where) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List<?> sequence)) {
            throw new EnvelopeException(where + " must be a sequence, found: " + describe(node));
        }
        List<String> values = new ArrayList<>();
        for (Object element : sequence) {
            values.add(string(element, where));
        }
        return values;
    }

    private static String string(Object node, String where) {
        if (!(node instanceof String value)) {
            throw new EnvelopeException(where + " must be a string, found: " + describe(node));
        }
        return value;
    }

    private static long longValue(Object node, String where) {
        if (!(node instanceof Number number)) {
            throw new EnvelopeException(where + " must be a number, found: " + describe(node));
        }
        return number.longValue();
    }

    private static int intValue(String value, String where) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new EnvelopeException(where + " must be a number, found: " + value);
        }
    }

    private static String describe(Object node) {
        return node == null ? "nothing" : node.getClass().getSimpleName();
    }
}
